package com.thinkaurelius.titan.graphdb.fulgora;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.util.concurrent.AbstractFuture;
import com.thinkaurelius.titan.core.TitanException;
import com.thinkaurelius.titan.core.TitanRelation;
import com.thinkaurelius.titan.core.olap.OLAPJob;
import com.thinkaurelius.titan.core.olap.OLAPResult;
import com.thinkaurelius.titan.core.olap.State;
import com.thinkaurelius.titan.core.olap.StateInitializer;
import com.thinkaurelius.titan.diskstorage.BackendTransaction;
import com.thinkaurelius.titan.diskstorage.Entry;
import com.thinkaurelius.titan.diskstorage.EntryList;
import com.thinkaurelius.titan.diskstorage.StaticBuffer;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.KeyIterator;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.SliceQuery;
import com.thinkaurelius.titan.diskstorage.util.BufferUtil;
import com.thinkaurelius.titan.diskstorage.util.RecordIterator;
import com.thinkaurelius.titan.diskstorage.util.StaticArrayEntry;
import com.thinkaurelius.titan.diskstorage.util.StaticArrayEntryList;
import com.thinkaurelius.titan.graphdb.idmanagement.IDManager;
import com.thinkaurelius.titan.graphdb.internal.InternalVertex;
import com.thinkaurelius.titan.graphdb.query.QueryExecutor;
import com.thinkaurelius.titan.graphdb.query.vertex.VertexCentricQuery;
import com.thinkaurelius.titan.graphdb.relations.RelationCache;
import com.thinkaurelius.titan.graphdb.transaction.RelationConstructor;
import com.thinkaurelius.titan.graphdb.transaction.StandardTitanTx;
import com.thinkaurelius.titan.graphdb.transaction.VertexFactory;
import com.thinkaurelius.titan.graphdb.types.system.BaseKey;
import com.thinkaurelius.titan.util.datastructures.Retriever;
import org.cliffc.high_scale_lib.NonBlockingHashMapLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
* @author Matthias Broecheler (me@matthiasb.com)
*/
class FulgoraExecutor<S extends State<S>> extends AbstractFuture<OLAPResult<S>> implements Runnable {

    private static final Logger log =
            LoggerFactory.getLogger(FulgoraExecutor.class);

    private static final int QUEUE_SIZE = 1000;
    private static final int TIMEOUT_MS = 60000; // 60 seconds

    private final SliceQuery[] queries;
    private final List<BlockingQueue<QueryResult>> dataQueues;
    private final DataPuller[] pullThreads;
    private final ThreadPoolExecutor processor;
    private final StandardTitanTx tx;
    private final IDManager idManager;
    private final OLAPJob job;

    final FulgoraResult<S> vertexStates;
    final String stateKey;
    final StateInitializer<S> initializer;

    private boolean processingException = false;

    FulgoraExecutor(final List<SliceQuery> sliceQueries, final StandardTitanTx tx, final IDManager idManager,
                    final int numProcessors, final String stateKey, final OLAPJob job,
                    final StateInitializer<S> initializer, final FulgoraResult<S> initialState) {
        this.tx=tx;
        this.stateKey = stateKey;
        this.job = job;
        this.initializer = initializer;
        BackendTransaction btx = tx.getTxHandle();
        this.idManager = idManager;

        //The first (0th) query is the grounding query
        dataQueues = new ArrayList<BlockingQueue<QueryResult>>(sliceQueries.size()+1);
        pullThreads = new DataPuller[sliceQueries.size()+1];
        queries = new SliceQuery[sliceQueries.size()+1];
        for (int i = 0; i <= sliceQueries.size(); i++) {
            if (i==0) queries[i]=new SliceQuery(BufferUtil.zeroBuffer(4),BufferUtil.oneBuffer(4)).setLimit(1);
            else queries[i]=sliceQueries.get(i-1);
            BlockingQueue<QueryResult> queue = new LinkedBlockingQueue<QueryResult>(QUEUE_SIZE);
            dataQueues.add(queue);
            pullThreads[i]=new DataPuller(idManager,queue,btx.edgeStoreKeys(queries[i]));
            pullThreads[i].start();
        }
        vertexStates = initialState;


        processor = new ThreadPoolExecutor(numProcessors, numProcessors, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(QUEUE_SIZE));
        processor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    }

    S getVertexState(long vertexId) {
        S state = vertexStates.get(vertexId);
        if (state==null) {
            state = initializer.initialState();
            vertexStates.set(vertexId, state);
        }
        return state;
    }

    void setVertexState(long vertexId, S state) {
        vertexStates.set(vertexId, state);
    }

    @Override
    public void run() {
        try {
            QueryResult[] currentResults = new QueryResult[queries.length];
            boolean encounteredPartitionedVertex = false;
            while (true) {
                for (int i = 0; i < queries.length; i++) {
                    if (currentResults[i]!=null) continue;
                    BlockingQueue<QueryResult> queue = dataQueues.get(i);

                    QueryResult qr = queue.poll(10,TimeUnit.MILLISECONDS); //Try very short time to see if we are done
                    if (qr==null) {
                        if (pullThreads[i].isFinished()) continue; //No more data to be expected
                        qr = queue.poll(TIMEOUT_MS,TimeUnit.MILLISECONDS); //otherwise, give it more time
                        if (qr==null && !pullThreads[i].isFinished())
                            throw new TitanException("Timed out waiting for next vertex data - storage error likely");
                    }
                    currentResults[i]=qr;
                }
                QueryResult conditionQuery = currentResults[0];
                currentResults[0]=null;
                if (conditionQuery==null) break; //Termination condition - primary query has no more data

                //First, check if this is a valid (non-deleted) vertex
                assert conditionQuery.entries.size()==1;
                RelationCache relCache = tx.getEdgeSerializer().parseRelation(
                                        conditionQuery.entries.get(0),true,tx);
                if (relCache.typeId != BaseKey.VertexExists.getID()) {
                    log.warn("Found deleted vertex with id: %s. Skipping",conditionQuery.vertexId);
                    for (int i=1;i<currentResults.length;i++) {
                        if (currentResults[i]!=null && currentResults[i].vertexId==conditionQuery.vertexId) {
                            currentResults[i]=null;
                        }
                    }
                } else {
                    FulgoraVertex vertex = new FulgoraVertex(tx,conditionQuery.vertexId,this);
                    if (idManager.isPartitionedVertex(conditionQuery.vertexId)) encounteredPartitionedVertex=true;
                    for (int i=1;i<currentResults.length;i++) {
                        if (currentResults[i]!=null && currentResults[i].vertexId==vertex.getID()) {
                            vertex.addToQueryCache(queries[i],currentResults[i].entries);
                            currentResults[i]=null;
                        }
                    }
                    processor.submit(new VertexProcessor<S>(vertex));

                }
            }
            processor.shutdown();
            processor.awaitTermination(TIMEOUT_MS,TimeUnit.MILLISECONDS);
            if (!processor.isTerminated()) throw new TitanException("Timed out waiting for vertex processors");
            for (int i = 0; i < pullThreads.length; i++) {
                pullThreads[i].join(10);
                if (pullThreads[i].isAlive()) throw new TitanException("Could not join data pulling thread");
            }
            tx.rollback();
            //Consolidate partitioned vertex states if such exist
            if (encounteredPartitionedVertex) {
                vertexStates.mergePartitionedVertexStates();
            }
            set(vertexStates);
        } catch (Throwable e) {
            log.error("Exception occured during job execution: {}",e);
            setException(e);
        } finally {
            processor.shutdownNow();
        }
    }

    final QueryExecutor<VertexCentricQuery, TitanRelation, SliceQuery> edgeProcessor = new QueryExecutor<VertexCentricQuery, TitanRelation, SliceQuery>() {

        @Override
        public Iterator<TitanRelation> getNew(VertexCentricQuery query) {
            return Iterators.emptyIterator();
        }

        @Override
        public boolean hasDeletions(VertexCentricQuery query) {
            return false;
        }

        @Override
        public boolean isDeleted(VertexCentricQuery query, TitanRelation result) {
            return false;
        }

        @Override
        public Iterator<TitanRelation> execute(VertexCentricQuery query, SliceQuery sq, Object exeInfo) {
            assert exeInfo==null;

            final InternalVertex vertex = query.getVertex();

            Iterable<Entry> iter = vertex.loadRelations(sq, new Retriever<SliceQuery, EntryList>() {
                @Override
                public EntryList get(SliceQuery query) {
                    return StaticArrayEntryList.EMPTY_LIST;
                }
            });

            return Iterables.transform(iter, new Function<Entry, TitanRelation>() {
                @Override
                public TitanRelation apply(@Nullable Entry data) {
                    return RelationConstructor.readRelation(vertex,data,tx.getEdgeSerializer(),tx,neighborVertices);
                }
            }).iterator();
        }
    };

    private final VertexFactory neighborVertices = new VertexFactory() {
        @Override
        public InternalVertex getInternalVertex(long id) {
            return new FulgoraNeighborVertex(id,FulgoraExecutor.this);
        }
    };


    private class VertexProcessor<S> implements Runnable {

        final FulgoraVertex vertex;

        private VertexProcessor(FulgoraVertex vertex) {
            this.vertex = vertex;
        }

        @Override
        public void run() {
            try {
                job.process(vertex);
            } catch (Throwable e) {
                log.error("Exception processing vertex ["+vertex.getID()+"]: ",e);
                processingException = true;
                vertexStates.set(vertex.getID(),null); //Invalidate state
            }

        }
    }

    private static class DataPuller extends Thread {

        private final BlockingQueue<QueryResult> queue;
        private final KeyIterator keyIter;
        private final IDManager idManager;
        private volatile boolean finished;

        private DataPuller(IDManager idManager, BlockingQueue<QueryResult> queue, KeyIterator keyIter) {
            this.queue = queue;
            this.keyIter = keyIter;
            this.idManager = idManager;
            this.finished = false;
        }

        @Override
        public void run() {
            try {
                while (keyIter.hasNext()) {
                    StaticBuffer key = keyIter.next();
                    RecordIterator<Entry> entries = keyIter.getEntries();
                    long vertexId = idManager.getKeyID(key);
                    if (IDManager.VertexIDType.Hidden.is(vertexId)) continue;
                    EntryList entryList = StaticArrayEntryList.ofStaticBuffer(entries, StaticArrayEntry.ENTRY_GETTER);
                    try {
                        queue.put(new QueryResult(vertexId,entryList));
                    } catch (InterruptedException e) {
                        log.error("Data-pulling thread interrupted while waiting on queue",e);
                        break;
                    }
                }
                finished = true;
            } catch (Throwable e) {
                log.error("Could not load data from storage: {}",e);
            } finally {
                try {
                    keyIter.close();
                } catch (IOException e) {
                    log.warn("Could not close storage iterator ", e);
                }
            }
        }

        public boolean isFinished() {
            return finished;
        }
    }

    private static class QueryResult {

        final EntryList entries;
        final long vertexId;

        private QueryResult(long vertexId, EntryList entries) {
            this.entries = entries;
            this.vertexId = vertexId;
        }
    }

}