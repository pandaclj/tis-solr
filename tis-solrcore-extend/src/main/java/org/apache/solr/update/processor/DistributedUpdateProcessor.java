/**
 * Copyright (c) 2020 QingLang, Inc. <baisui@qlangtech.com>
 *
 * This program is free software: you can use, redistribute, and/or modify
 * it under the terms of the GNU Affero General Public License, version 3
 * or later ("AGPL"), as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.apache.solr.update.processor;

import com.google.common.annotations.VisibleForTesting;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CharsRefBuilder;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrRequest.METHOD;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.response.SimpleSolrResponse;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.params.*;
import org.apache.solr.common.util.Hash;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.TimeSource;
import org.apache.solr.handler.component.RealTimeGetComponent;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.update.*;
import org.apache.solr.update.SolrCmdDistributor.Error;
import org.apache.solr.update.SolrCmdDistributor.Node;
import org.apache.solr.util.TestInjection;
import org.apache.solr.util.TimeOut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import static org.apache.solr.common.params.CommonParams.DISTRIB;
import static org.apache.solr.update.processor.DistributingUpdateProcessorFactory.DISTRIB_UPDATE_PARAM;

/**
 TODO: we really should not wait for distrib after local? unless a certain replication factor is asked for
 *
 * @author 百岁（baisui@qlangtech.com）
 * @date 2020/09/25
 */
public class DistributedUpdateProcessor extends UpdateRequestProcessor {

    static final String PARAM_WHITELIST_CTX_KEY = DistributedUpdateProcessor.class + "PARAM_WHITELIST_CTX_KEY";

    public static final String DISTRIB_FROM_SHARD = "distrib.from.shard";

    public static final String DISTRIB_FROM_COLLECTION = "distrib.from.collection";

    public static final String DISTRIB_FROM_PARENT = "distrib.from.parent";

    public static final String DISTRIB_FROM = "distrib.from";

    public static final String DISTRIB_INPLACE_PREVVERSION = "distrib.inplace.prevversion";

    protected static final String TEST_DISTRIB_SKIP_SERVERS = "test.distrib.skip.servers";

    private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /**
     * Request forwarded to a leader of a different shard will be retried up to this amount of times by default
     */
    static final int MAX_RETRIES_ON_FORWARD_DEAULT = Integer.getInteger("solr.retries.on.forward", 25);

    /**
     * Requests from leader to it's followers will be retried this amount of times by default
     */
    static final int MAX_RETRIES_TO_FOLLOWERS_DEFAULT = Integer.getInteger("solr.retries.to.followers", 3);

    /**
     * Values this processor supports for the <code>DISTRIB_UPDATE_PARAM</code>.
     * This is an implementation detail exposed solely for tests.
     *
     * @see DistributingUpdateProcessorFactory#DISTRIB_UPDATE_PARAM
     */
    public static enum DistribPhase {

        NONE, TOLEADER, FROMLEADER;

        public static DistribPhase parseParam(final String param) {
            if (param == null || param.trim().isEmpty()) {
                return NONE;
            }
            try {
                return valueOf(param);
            } catch (IllegalArgumentException e) {
                throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Illegal value for " + DISTRIB_UPDATE_PARAM + ": " + param, e);
            }
        }
    }

    public static final String COMMIT_END_POINT = "commit_end_point";

    public static final String LOG_REPLAY = "log_replay";

    // used to assert we don't call finish more than once, see finish()
    private boolean finished = false;

    protected final SolrQueryRequest req;

    protected final SolrQueryResponse rsp;

    private final AtomicUpdateDocumentMerger docMerger;

    private final UpdateLog ulog;

    @VisibleForTesting
    VersionInfo vinfo;

    private final boolean versionsStored;

    private boolean returnVersions;

    private NamedList<Object> addsResponse = null;

    private NamedList<Object> deleteResponse = null;

    private NamedList<Object> deleteByQueryResponse = null;

    private CharsRefBuilder scratch;

    private final SchemaField idField;

    // these are setup at the start of each request processing
    // method in this update processor
    protected boolean isLeader = true;

    protected boolean forwardToLeader = false;

    protected boolean isSubShardLeader = false;

    protected boolean isIndexChanged = false;

    /**
     * Number of times requests forwarded to some other shard's leader can be retried
     */
    protected final int maxRetriesOnForward = MAX_RETRIES_ON_FORWARD_DEAULT;

    /**
     * Number of times requests from leaders to followers can be retried
     */
    protected final int maxRetriesToFollowers = MAX_RETRIES_TO_FOLLOWERS_DEFAULT;

    // the current command this processor is working on.
    protected UpdateCommand updateCommand;

    protected final Replica.Type replicaType;

    public DistributedUpdateProcessor(SolrQueryRequest req, SolrQueryResponse rsp, UpdateRequestProcessor next) {
        this(req, rsp, new AtomicUpdateDocumentMerger(req), next);
    }

    /**
     * Specification of AtomicUpdateDocumentMerger is currently experimental.
     *
     * @lucene.experimental
     */
    public DistributedUpdateProcessor(SolrQueryRequest req, SolrQueryResponse rsp, AtomicUpdateDocumentMerger docMerger, UpdateRequestProcessor next) {
        super(next);
        this.rsp = rsp;
        this.docMerger = docMerger;
        this.idField = req.getSchema().getUniqueKeyField();
        this.req = req;
        this.replicaType = computeReplicaType();
        // version init
        this.ulog = req.getCore().getUpdateHandler().getUpdateLog();
        this.vinfo = ulog == null ? null : ulog.getVersionInfo();
        versionsStored = this.vinfo != null && this.vinfo.getVersionField() != null;
        returnVersions = req.getParams().getBool(UpdateParams.VERSIONS, false);
        // TODO: better way to get the response, or pass back info to it?
        // SolrRequestInfo reqInfo = returnVersions ? SolrRequestInfo.getRequestInfo() : null;
        // this should always be used - see filterParams
        TisDistributedUpdateProcessorFactory.addParamToDistributedRequestWhitelist(this.req, UpdateParams.UPDATE_CHAIN, TEST_DISTRIB_SKIP_SERVERS, CommonParams.VERSION_FIELD, UpdateParams.EXPUNGE_DELETES, UpdateParams.OPTIMIZE, UpdateParams.MAX_OPTIMIZE_SEGMENTS, ShardParams._ROUTE_);
    // this.rsp = reqInfo != null ? reqInfo.getRsp() : null;
    }

    /**
     * @return the replica type of the collection.
     */
    protected Replica.Type computeReplicaType() {
        return Replica.Type.NRT;
    }

    boolean isLeader() {
        return isLeader;
    }

    @Override
    public void processAdd(AddUpdateCommand cmd) throws IOException {
        assert TestInjection.injectFailUpdateRequests();
        setupRequest(cmd);
        // If we were sent a previous version, set this to the AddUpdateCommand (if not already set)
        if (!cmd.isInPlaceUpdate()) {
            cmd.prevVersion = cmd.getReq().getParams().getLong(DistributedUpdateProcessor.DISTRIB_INPLACE_PREVVERSION, -1);
        }
        // TODO: if minRf > 1 and we know the leader is the only active replica, we could fail
        // the request right here but for now I think it is better to just return the status
        // to the client that the minRf wasn't reached and let them handle it
        boolean dropCmd = false;
        if (!forwardToLeader) {
            dropCmd = versionAdd(cmd);
        }
        if (dropCmd) {
            // TODO: do we need to add anything to the response?
            return;
        }
        doDistribAdd(cmd);
        // TODO: what to do when no idField?
        if (returnVersions && rsp != null && idField != null) {
            if (addsResponse == null) {
                addsResponse = new NamedList<>(1);
                rsp.add("adds", addsResponse);
            }
            if (scratch == null)
                scratch = new CharsRefBuilder();
            idField.getType().indexedToReadable(cmd.getIndexedId(), scratch);
            addsResponse.add(scratch.toString(), cmd.getVersion());
        }
    // TODO: keep track of errors?  needs to be done at a higher level though since
    // an id may fail before it gets to this processor.
    // Given that, it may also make sense to move the version reporting out of this
    // processor too.
    }

    protected void doDistribAdd(AddUpdateCommand cmd) throws IOException {
    // no-op for derived classes to implement
    }

    // must be synchronized by bucket
    private void doLocalAdd(AddUpdateCommand cmd) throws IOException {
        super.processAdd(cmd);
        isIndexChanged = true;
    }

    // must be synchronized by bucket
    private void doLocalDelete(DeleteUpdateCommand cmd) throws IOException {
        super.processDelete(cmd);
        isIndexChanged = true;
    }

    public static int bucketHash(BytesRef idBytes) {
        assert idBytes != null;
        return Hash.murmurhash3_x86_32(idBytes.bytes, idBytes.offset, idBytes.length, 0);
    }

    /**
     * @return whether or not to drop this cmd
     * @throws IOException If there is a low-level I/O error.
     */
    protected boolean versionAdd(AddUpdateCommand cmd) throws IOException {
        BytesRef idBytes = cmd.getIndexedId();
        if (idBytes == null) {
            super.processAdd(cmd);
            return false;
        }
        if (vinfo == null) {
            if (AtomicUpdateDocumentMerger.isAtomicUpdate(cmd)) {
                throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Atomic document updates are not supported unless <updateLog/> is configured");
            } else {
                super.processAdd(cmd);
                return false;
            }
        }
        // This is only the hash for the bucket, and must be based only on the uniqueKey (i.e. do not use a pluggable hash
        // here)
        int bucketHash = bucketHash(idBytes);
        // at this point, there is an update we need to try and apply.
        // we may or may not be the leader.
        // Find any existing version in the document
        // TODO: don't reuse update commands any more!
        long versionOnUpdate = cmd.getVersion();
        if (versionOnUpdate == 0) {
            SolrInputField versionField = cmd.getSolrInputDocument().getField(CommonParams.VERSION_FIELD);
            if (versionField != null) {
                Object o = versionField.getValue();
                versionOnUpdate = o instanceof Number ? ((Number) o).longValue() : Long.parseLong(o.toString());
            } else {
                // Find the version
                String versionOnUpdateS = req.getParams().get(CommonParams.VERSION_FIELD);
                versionOnUpdate = versionOnUpdateS == null ? 0 : Long.parseLong(versionOnUpdateS);
            }
        }
        boolean isReplayOrPeersync = (cmd.getFlags() & (UpdateCommand.REPLAY | UpdateCommand.PEER_SYNC)) != 0;
        boolean leaderLogic = isLeader && !isReplayOrPeersync;
        boolean forwardedFromCollection = cmd.getReq().getParams().get(DISTRIB_FROM_COLLECTION) != null;
        VersionBucket bucket = vinfo.bucket(bucketHash);
        long dependentVersionFound = -1;
        // this update depends), before entering the synchronized block
        if (!leaderLogic && cmd.isInPlaceUpdate()) {
            dependentVersionFound = waitForDependentUpdates(cmd, versionOnUpdate, isReplayOrPeersync, bucket);
            if (dependentVersionFound == -1) {
                // it means the document has been deleted by now at the leader. drop this update
                return true;
            }
        }
        vinfo.lockForUpdate();
        try {
            long finalVersionOnUpdate = versionOnUpdate;
            return bucket.runWithLock(vinfo.getVersionBucketLockTimeoutMs(), () -> doVersionAdd(cmd, finalVersionOnUpdate, isReplayOrPeersync, leaderLogic, forwardedFromCollection, bucket));
        } finally {
            vinfo.unlockForUpdate();
        }
    }

    private boolean doVersionAdd(AddUpdateCommand cmd, long versionOnUpdate, boolean isReplayOrPeersync, boolean leaderLogic, boolean forwardedFromCollection, VersionBucket bucket) throws IOException {
        try {
            BytesRef idBytes = cmd.getIndexedId();
            bucket.signalAll();
            if (versionsStored) {
                long bucketVersion = bucket.highest;
                if (leaderLogic) {
                    if (forwardedFromCollection && ulog.getState() == UpdateLog.State.ACTIVE) {
                        // see SOLR-5308
                        if (log.isInfoEnabled()) {
                            log.info("Removing version field from doc: {}", cmd.getPrintableId());
                        }
                        cmd.solrDoc.remove(CommonParams.VERSION_FIELD);
                        versionOnUpdate = 0;
                    }
                    getUpdatedDocument(cmd, versionOnUpdate);
                    // leaders can also be in buffering state during "migrate" API call, see SOLR-5308
                    if (forwardedFromCollection && ulog.getState() != UpdateLog.State.ACTIVE && isReplayOrPeersync == false) {
                        // we're not in an active state, and this update isn't from a replay, so buffer it.
                        if (log.isInfoEnabled()) {
                            log.info("Leader logic applied but update log is buffering: {}", cmd.getPrintableId());
                        }
                        cmd.setFlags(cmd.getFlags() | UpdateCommand.BUFFERING);
                        ulog.add(cmd);
                        return true;
                    }
                    if (versionOnUpdate != 0) {
                    // Long lastVersion = vinfo.lookupVersion(cmd.getIndexedId());
                    // long foundVersion = lastVersion == null ? -1 : lastVersion;
                    // if (versionOnUpdate == foundVersion || (versionOnUpdate < 0 && foundVersion < 0)
                    // || (versionOnUpdate == 1 && foundVersion > 0)) {
                    // // we're ok if versions match, or if both are negative (all missing docs are equal), or if cmd
                    // // specified it must exist (versionOnUpdate==1) and it does.
                    // } else {
                    // if(cmd.getReq().getParams().getBool(CommonParams.FAIL_ON_VERSION_CONFLICTS, true) == false) {
                    // return true;
                    // }
                    // 
                    // throw new SolrException(ErrorCode.CONFLICT, "version conflict for " + cmd.getPrintableId()
                    // + " expected=" + versionOnUpdate + " actual=" + foundVersion);
                    // }
                    }
                    // long version = vinfo.getNewClock();
                    // cmd.setVersion(version);
                    // cmd.getSolrInputDocument().setField(CommonParams.VERSION_FIELD, version);
                    // bucket.updateHighest(version);
                    cmd.setVersion(versionOnUpdate);
                    // baisui modify to 'versionOnUpdate'
                    cmd.getSolrInputDocument().setField(CommonParams.VERSION_FIELD, // 'versionOnUpdate'
                    versionOnUpdate);
                } else {
                    // The leader forwarded us this update.
                    cmd.setVersion(versionOnUpdate);
                    if (shouldBufferUpdate(cmd, isReplayOrPeersync, ulog.getState())) {
                        // we're not in an active state, and this update isn't from a replay, so buffer it.
                        cmd.setFlags(cmd.getFlags() | UpdateCommand.BUFFERING);
                        ulog.add(cmd);
                        return true;
                    }
                    if (willBeDropped(cmd, idBytes, versionOnUpdate)) {
                        return true;
                    }
                    // }
                    if (!isSubShardLeader && replicaType == Replica.Type.TLOG && (cmd.getFlags() & UpdateCommand.REPLAY) == 0) {
                        cmd.setFlags(cmd.getFlags() | UpdateCommand.IGNORE_INDEXWRITER);
                    }
                }
            }
            SolrInputDocument clonedDoc = shouldCloneCmdDoc() ? cmd.solrDoc.deepCopy() : null;
            // TODO: possibly set checkDeleteByQueries as a flag on the command?
            doLocalAdd(cmd);
            // This refresh makes RTG handler aware of this update.q
            if (req.getSchema().isUsableForChildDocs() && shouldRefreshUlogCaches(cmd)) {
                ulog.openRealtimeSearcher();
            }
            if (clonedDoc != null) {
                cmd.solrDoc = clonedDoc;
            }
        } finally {
            bucket.unlock();
        }
        return false;
    }

    /**
     * @param cmd
     * @param idBytes
     * @param versionOnUpdate
     */
    private boolean willBeDropped(AddUpdateCommand cmd, BytesRef idBytes, long versionOnUpdate) {
        // there have been updates higher than the current
        // update. we need to check
        // the specific version for this id.
        // 这个version是从commitlog的内存中取得的
        Long lastVersion = vinfo.lookupVersion(cmd.getIndexedId());
        if (lastVersion != null && Math.abs(lastVersion) >= versionOnUpdate) {
            // This update is a repeat, or was reordered. We
            // need to drop this update.
            log.warn("Dropping add update due to version {}", idBytes.utf8ToString() + ",lastVersion:" + lastVersion + ",versionOnUpdate:" + versionOnUpdate);
            return true;
        }
        return false;
    }

    /**
     * @return whether cmd doc should be cloned before localAdd
     */
    protected boolean shouldCloneCmdDoc() {
        return false;
    }

    @VisibleForTesting
    boolean shouldBufferUpdate(AddUpdateCommand cmd, boolean isReplayOrPeersync, UpdateLog.State state) {
        if (state == UpdateLog.State.APPLYING_BUFFERED && !isReplayOrPeersync && !cmd.isInPlaceUpdate()) {
            // this a new update sent from the leader, it contains whole document therefore it won't depend on other updates
            return false;
        }
        return state != UpdateLog.State.ACTIVE && isReplayOrPeersync == false;
    }

    /**
     * This method checks the update/transaction logs and index to find out if the update ("previous update") that the current update
     * depends on (in the case that this current update is an in-place update) has already been completed. If not,
     * this method will wait for the missing update until it has arrived. If it doesn't arrive within a timeout threshold,
     * then this actively fetches from the leader.
     *
     * @return -1 if the current in-place should be dropped, or last found version if previous update has been indexed.
     */
    private long waitForDependentUpdates(AddUpdateCommand cmd, long versionOnUpdate, boolean isReplayOrPeersync, VersionBucket bucket) throws IOException {
        long lastFoundVersion = 0;
        TimeOut waitTimeout = new TimeOut(5, TimeUnit.SECONDS, TimeSource.NANO_TIME);
        vinfo.lockForUpdate();
        try {
            lastFoundVersion = bucket.runWithLock(vinfo.getVersionBucketLockTimeoutMs(), () -> doWaitForDependentUpdates(cmd, versionOnUpdate, isReplayOrPeersync, bucket, waitTimeout));
        } finally {
            vinfo.unlockForUpdate();
        }
        if (Math.abs(lastFoundVersion) > cmd.prevVersion) {
            // we can drop the current update.
            if (log.isDebugEnabled()) {
                log.debug("Update was applied on version: {}, but last version I have is: {} . Current update should be dropped. id={}", cmd.prevVersion, lastFoundVersion, cmd.getPrintableId());
            }
            return -1;
        } else if (Math.abs(lastFoundVersion) == cmd.prevVersion) {
            assert 0 < lastFoundVersion : "prevVersion " + cmd.prevVersion + " found but is a delete!";
            if (log.isDebugEnabled()) {
                log.debug("Dependent update found. id={}", cmd.getPrintableId());
            }
            return lastFoundVersion;
        }
        // We have waited enough, but dependent update didn't arrive. Its time to actively fetch it from leader
        if (log.isInfoEnabled()) {
            log.info("Missing update, on which current in-place update depends on, hasn't arrived. id={}, looking for version={}, last found version={}", cmd.getPrintableId(), cmd.prevVersion, lastFoundVersion);
        }
        UpdateCommand missingUpdate = fetchFullUpdateFromLeader(cmd, versionOnUpdate);
        if (missingUpdate instanceof DeleteUpdateCommand) {
            if (log.isInfoEnabled()) {
                log.info("Tried to fetch document {} from the leader, but the leader says document has been deleted. Deleting the document here and skipping this update: Last found version: {}, was looking for: {}", cmd.getPrintableId(), lastFoundVersion, cmd.prevVersion);
            }
            versionDelete((DeleteUpdateCommand) missingUpdate);
            return -1;
        } else {
            assert missingUpdate instanceof AddUpdateCommand;
            if (log.isDebugEnabled()) {
                log.debug("Fetched the document: {}", ((AddUpdateCommand) missingUpdate).getSolrInputDocument());
            }
            versionAdd((AddUpdateCommand) missingUpdate);
            if (log.isInfoEnabled()) {
                log.info("Added the fetched document, id= {}, version={}", ((AddUpdateCommand) missingUpdate).getPrintableId(), missingUpdate.getVersion());
            }
        }
        return missingUpdate.getVersion();
    }

    private long doWaitForDependentUpdates(AddUpdateCommand cmd, long versionOnUpdate, boolean isReplayOrPeersync, VersionBucket bucket, TimeOut waitTimeout) {
        long lastFoundVersion;
        try {
            Long lookedUpVersion = vinfo.lookupVersion(cmd.getIndexedId());
            lastFoundVersion = lookedUpVersion == null ? 0L : lookedUpVersion;
            if (Math.abs(lastFoundVersion) < cmd.prevVersion) {
                if (log.isDebugEnabled()) {
                    log.debug("Re-ordered inplace update. version={}, prevVersion={}, lastVersion={}, replayOrPeerSync={}, id={}", (cmd.getVersion() == 0 ? versionOnUpdate : cmd.getVersion()), cmd.prevVersion, lastFoundVersion, isReplayOrPeersync, cmd.getPrintableId());
                }
            }
            while (Math.abs(lastFoundVersion) < cmd.prevVersion && !waitTimeout.hasTimedOut()) {
                long timeLeftInNanos = waitTimeout.timeLeft(TimeUnit.NANOSECONDS);
                if (timeLeftInNanos > 0) {
                    // 0 means: wait forever until notified, but we don't want that.
                    bucket.awaitNanos(timeLeftInNanos);
                }
                lookedUpVersion = vinfo.lookupVersion(cmd.getIndexedId());
                lastFoundVersion = lookedUpVersion == null ? 0L : lookedUpVersion;
            }
        } finally {
            bucket.unlock();
        }
        return lastFoundVersion;
    }

    /**
     * This method is used when an update on which a particular in-place update has been lost for some reason. This method
     * sends a request to the shard leader to fetch the latest full document as seen on the leader.
     *
     * @return AddUpdateCommand containing latest full doc at shard leader for the given id, or null if not found.
     */
    private UpdateCommand fetchFullUpdateFromLeader(AddUpdateCommand inplaceAdd, long versionOnUpdate) throws IOException {
        String id = inplaceAdd.getPrintableId();
        UpdateShardHandler updateShardHandler = inplaceAdd.getReq().getCore().getCoreContainer().getUpdateShardHandler();
        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set(DISTRIB, false);
        params.set("getInputDocument", id);
        params.set("onlyIfActive", true);
        SolrRequest<SimpleSolrResponse> ur = new GenericSolrRequest(METHOD.GET, "/get", params);
        String leaderUrl = getLeaderUrl(id);
        if (leaderUrl == null) {
            throw new SolrException(ErrorCode.SERVER_ERROR, "Can't find document with id=" + id);
        }
        NamedList<Object> rsp;
        try {
            ur.setBasePath(leaderUrl);
            rsp = updateShardHandler.getUpdateOnlyHttpClient().request(ur);
        } catch (SolrServerException e) {
            throw new SolrException(ErrorCode.SERVER_ERROR, "Error during fetching [" + id + "] from leader (" + leaderUrl + "): ", e);
        }
        Object inputDocObj = rsp.get("inputDocument");
        Long version = (Long) rsp.get("version");
        SolrInputDocument leaderDoc = (SolrInputDocument) inputDocObj;
        if (leaderDoc == null) {
            // this doc was not found (deleted) on the leader. Lets delete it here as well.
            DeleteUpdateCommand del = new DeleteUpdateCommand(inplaceAdd.getReq());
            del.setIndexedId(inplaceAdd.getIndexedId());
            del.setId(inplaceAdd.getIndexedId().utf8ToString());
            del.setVersion((version == null || version == 0) ? -versionOnUpdate : version);
            return del;
        }
        AddUpdateCommand cmd = new AddUpdateCommand(req);
        cmd.solrDoc = leaderDoc;
        cmd.setVersion((long) leaderDoc.getFieldValue(CommonParams.VERSION_FIELD));
        return cmd;
    }

    // TODO: may want to switch to using optimistic locking in the future for better concurrency
    // that's why this code is here... need to retry in a loop closely around/in versionAdd
    boolean getUpdatedDocument(AddUpdateCommand cmd, long versionOnUpdate) throws IOException {
        if (!AtomicUpdateDocumentMerger.isAtomicUpdate(cmd))
            return false;
        Set<String> inPlaceUpdatedFields = AtomicUpdateDocumentMerger.computeInPlaceUpdatableFields(cmd);
        if (inPlaceUpdatedFields.size() > 0) {
            // non-empty means this is suitable for in-place updates
            if (docMerger.doInPlaceUpdateMerge(cmd, inPlaceUpdatedFields)) {
                return true;
            } else {
            // in-place update failed, so fall through and re-try the same with a full atomic update
            }
        }
        // full (non-inplace) atomic update
        SolrInputDocument sdoc = cmd.getSolrInputDocument();
        BytesRef idBytes = cmd.getIndexedId();
        String idString = cmd.getPrintableId();
        SolrInputDocument oldRootDocWithChildren = RealTimeGetComponent.getInputDocument(cmd.getReq().getCore(), idBytes, RealTimeGetComponent.Resolution.ROOT_WITH_CHILDREN);
        if (oldRootDocWithChildren == null) {
            if (versionOnUpdate > 0) {
                // could just let the optimistic locking throw the error
                throw new SolrException(ErrorCode.CONFLICT, "Document not found for update.  id=" + idString);
            } else if (req.getParams().get(ShardParams._ROUTE_) != null) {
                // and was explicitly routed using _route_
                throw new SolrException(ErrorCode.BAD_REQUEST, "Could not find document id=" + idString + ", perhaps the wrong \"_route_\" param was supplied");
            }
        } else {
            oldRootDocWithChildren.remove(CommonParams.VERSION_FIELD);
        }
        SolrInputDocument mergedDoc;
        if (idField == null || oldRootDocWithChildren == null) {
            // create a new doc by default if an old one wasn't found
            mergedDoc = docMerger.merge(sdoc, new SolrInputDocument());
        } else {
            // Safety check: don't allow an update to an existing doc that has children, unless we actually support this.
            if (// however, next line we see it doesn't support child docs
            req.getSchema().isUsableForChildDocs() && req.getSchema().supportsPartialUpdatesOfChildDocs() == false && req.getSearcher().count(new TermQuery(new Term(IndexSchema.ROOT_FIELD_NAME, idBytes))) > 1) {
                throw new SolrException(ErrorCode.BAD_REQUEST, "This schema does not support partial updates to nested docs. See ref guide.");
            }
            String oldRootDocRootFieldVal = (String) oldRootDocWithChildren.getFieldValue(IndexSchema.ROOT_FIELD_NAME);
            if (req.getSchema().savesChildDocRelations() && oldRootDocRootFieldVal != null && !idString.equals(oldRootDocRootFieldVal)) {
                // this is an update where the updated doc is not the root document
                SolrInputDocument sdocWithChildren = RealTimeGetComponent.getInputDocument(cmd.getReq().getCore(), idBytes, RealTimeGetComponent.Resolution.DOC_WITH_CHILDREN);
                mergedDoc = docMerger.mergeChildDoc(sdoc, oldRootDocWithChildren, sdocWithChildren);
            } else {
                mergedDoc = docMerger.merge(sdoc, oldRootDocWithChildren);
            }
        }
        cmd.solrDoc = mergedDoc;
        return true;
    }

    @Override
    public void processDelete(DeleteUpdateCommand cmd) throws IOException {
        assert TestInjection.injectFailUpdateRequests();
        updateCommand = cmd;
        if (!cmd.isDeleteById()) {
            doDeleteByQuery(cmd);
        } else {
            doDeleteById(cmd);
        }
    }

    // Implementing min_rf here was a bit tricky. When a request comes in for a delete by id to a replica that does _not_
    // have any documents specified by those IDs, the request is not forwarded to any other replicas on that shard. Thus
    // we have to spoof the replicationTracker and set the achieved rf to the number of active replicas.
    // 
    protected void doDeleteById(DeleteUpdateCommand cmd) throws IOException {
        setupRequest(cmd);
        boolean dropCmd = false;
        if (!forwardToLeader) {
            dropCmd = versionDelete(cmd);
        }
        if (dropCmd) {
            // TODO: do we need to add anything to the response?
            return;
        }
        doDistribDeleteById(cmd);
        // TODO: what to do when no idField?
        if (returnVersions && rsp != null && cmd.getIndexedId() != null && idField != null) {
            if (deleteResponse == null) {
                deleteResponse = new NamedList<>(1);
                rsp.add("deletes", deleteResponse);
            }
            if (scratch == null)
                scratch = new CharsRefBuilder();
            idField.getType().indexedToReadable(cmd.getIndexedId(), scratch);
            // we're returning the version of the delete.. not the version of the doc we deleted.
            deleteResponse.add(scratch.toString(), cmd.getVersion());
        }
    }

    /**
     * This method can be overridden to tamper with the cmd after the localDeleteById operation
     *
     * @param cmd the delete command
     * @throws IOException in case post processing failed
     */
    protected void doDistribDeleteById(DeleteUpdateCommand cmd) throws IOException {
    // no-op for derived classes to implement
    }

    /**
     * @see DistributedUpdateProcessorFactory#addParamToDistributedRequestWhitelist
     */
    @SuppressWarnings("unchecked")
    protected ModifiableSolrParams filterParams(SolrParams params) {
        ModifiableSolrParams fparams = new ModifiableSolrParams();
        Set<String> whitelist = (Set<String>) this.req.getContext().get(PARAM_WHITELIST_CTX_KEY);
        assert null != whitelist : "whitelist can't be null, constructor adds to it";
        for (String p : whitelist) {
            passParam(params, fparams, p);
        }
        return fparams;
    }

    private void passParam(SolrParams params, ModifiableSolrParams fparams, String param) {
        String[] values = params.getParams(param);
        if (values != null) {
            for (String value : values) {
                fparams.add(param, value);
            }
        }
    }

    /**
     * for implementing classes to setup request data(nodes, replicas)
     *
     * @param cmd the delete command being processed
     */
    protected void doDeleteByQuery(DeleteUpdateCommand cmd) throws IOException {
        // even in non zk mode, tests simulate updates from a leader
        setupRequest(cmd);
        doDeleteByQuery(cmd, null, null);
    }

    /**
     * should be called by implementing class after setting up replicas
     *
     * @param cmd      delete command
     * @param replicas list of Nodes replicas to pass to {@link DistributedUpdateProcessor#doDistribDeleteByQuery(DeleteUpdateCommand, List, DocCollection)}
     * @param coll     the collection in zookeeper {@link org.apache.solr.common.cloud.DocCollection},
     *                 passed to {@link DistributedUpdateProcessor#doDistribDeleteByQuery(DeleteUpdateCommand, List, DocCollection)}
     */
    protected void doDeleteByQuery(DeleteUpdateCommand cmd, List<SolrCmdDistributor.Node> replicas, DocCollection coll) throws IOException {
        if (vinfo == null) {
            super.processDelete(cmd);
            return;
        }
        // at this point, there is an update we need to try and apply.
        // we may or may not be the leader.
        versionDeleteByQuery(cmd);
        doDistribDeleteByQuery(cmd, replicas, coll);
        if (returnVersions && rsp != null) {
            if (deleteByQueryResponse == null) {
                deleteByQueryResponse = new NamedList<>(1);
                rsp.add("deleteByQuery", deleteByQueryResponse);
            }
            deleteByQueryResponse.add(cmd.getQuery(), cmd.getVersion());
        }
    }

    /**
     * This runs after versionDeleteByQuery is invoked, should be used to tamper or forward DeleteCommand
     *
     * @param cmd      delete command
     * @param replicas list of Nodes replicas
     * @param coll     the collection in zookeeper {@link org.apache.solr.common.cloud.DocCollection}.
     * @throws IOException in case post processing failed
     */
    protected void doDistribDeleteByQuery(DeleteUpdateCommand cmd, List<Node> replicas, DocCollection coll) throws IOException {
    // no-op for derived classes to implement
    }

    protected void versionDeleteByQuery(DeleteUpdateCommand cmd) throws IOException {
        // Find the version
        long versionOnUpdate = findVersionOnUpdate(cmd);
        boolean isReplayOrPeersync = (cmd.getFlags() & (UpdateCommand.REPLAY | UpdateCommand.PEER_SYNC)) != 0;
        boolean leaderLogic = isLeader && !isReplayOrPeersync;
        if (!leaderLogic && versionOnUpdate == 0) {
            throw new SolrException(ErrorCode.BAD_REQUEST, "missing _version_ on update from leader");
        }
        vinfo.blockUpdates();
        try {
            doLocalDeleteByQuery(cmd, versionOnUpdate, isReplayOrPeersync);
        // since we don't know which documents were deleted, the easiest thing to do is to invalidate
        // all real-time caches (i.e. UpdateLog) which involves also getting a new version of the IndexReader
        // (so cache misses will see up-to-date data)
        } finally {
            vinfo.unblockUpdates();
        }
    }

    private long findVersionOnUpdate(UpdateCommand cmd) {
        long versionOnUpdate = cmd.getVersion();
        if (versionOnUpdate == 0) {
            String versionOnUpdateS = req.getParams().get(CommonParams.VERSION_FIELD);
            versionOnUpdate = versionOnUpdateS == null ? 0 : Long.parseLong(versionOnUpdateS);
        }
        // normalize to positive version
        versionOnUpdate = Math.abs(versionOnUpdate);
        return versionOnUpdate;
    }

    private void doLocalDeleteByQuery(DeleteUpdateCommand cmd, long versionOnUpdate, boolean isReplayOrPeersync) throws IOException {
        if (versionsStored) {
            final boolean leaderLogic = isLeader & !isReplayOrPeersync;
            if (leaderLogic) {
                long version = vinfo.getNewClock();
                cmd.setVersion(-version);
                // TODO update versions in all buckets
                doLocalDelete(cmd);
            } else {
                cmd.setVersion(-versionOnUpdate);
                if (ulog.getState() != UpdateLog.State.ACTIVE && isReplayOrPeersync == false) {
                    // we're not in an active state, and this update isn't from a replay, so buffer it.
                    cmd.setFlags(cmd.getFlags() | UpdateCommand.BUFFERING);
                    ulog.deleteByQuery(cmd);
                    return;
                }
                if (!isSubShardLeader && replicaType == Replica.Type.TLOG && (cmd.getFlags() & UpdateCommand.REPLAY) == 0) {
                    // TLOG replica not leader, don't write the DBQ to IW
                    cmd.setFlags(cmd.getFlags() | UpdateCommand.IGNORE_INDEXWRITER);
                }
                doLocalDelete(cmd);
            }
        }
    }

    // internal helper method to setup request by processors who use this class.
    // NOTE: not called by this class!
    void setupRequest(UpdateCommand cmd) {
        updateCommand = cmd;
        isLeader = getNonZkLeaderAssumption(req);
    }

    /**
     * @param id id of doc
     * @return url of leader, or null if not found.
     */
    protected String getLeaderUrl(String id) {
        return req.getParams().get(DISTRIB_FROM);
    }

    protected boolean versionDelete(DeleteUpdateCommand cmd) throws IOException {
        BytesRef idBytes = cmd.getIndexedId();
        if (vinfo == null || idBytes == null) {
            super.processDelete(cmd);
            return false;
        }
        // This is only the hash for the bucket, and must be based only on the uniqueKey (i.e. do not use a pluggable hash
        // here)
        int bucketHash = bucketHash(idBytes);
        // at this point, there is an update we need to try and apply.
        // we may or may not be the leader.
        // Find the version
        long versionOnUpdate = cmd.getVersion();
        if (versionOnUpdate == 0) {
            String versionOnUpdateS = req.getParams().get(CommonParams.VERSION_FIELD);
            versionOnUpdate = versionOnUpdateS == null ? 0 : Long.parseLong(versionOnUpdateS);
        }
        long signedVersionOnUpdate = versionOnUpdate;
        // normalize to positive version
        versionOnUpdate = Math.abs(versionOnUpdate);
        boolean isReplayOrPeersync = (cmd.getFlags() & (UpdateCommand.REPLAY | UpdateCommand.PEER_SYNC)) != 0;
        boolean leaderLogic = isLeader && !isReplayOrPeersync;
        boolean forwardedFromCollection = cmd.getReq().getParams().get(DISTRIB_FROM_COLLECTION) != null;
        if (!leaderLogic && versionOnUpdate == 0) {
            throw new SolrException(ErrorCode.BAD_REQUEST, "missing _version_ on update from leader");
        }
        VersionBucket bucket = vinfo.bucket(bucketHash);
        vinfo.lockForUpdate();
        try {
            long finalVersionOnUpdate = versionOnUpdate;
            return bucket.runWithLock(vinfo.getVersionBucketLockTimeoutMs(), () -> doVersionDelete(cmd, finalVersionOnUpdate, signedVersionOnUpdate, isReplayOrPeersync, leaderLogic, forwardedFromCollection, bucket));
        } finally {
            vinfo.unlockForUpdate();
        }
    }

    private boolean doVersionDelete(DeleteUpdateCommand cmd, long versionOnUpdate, long signedVersionOnUpdate, boolean isReplayOrPeersync, boolean leaderLogic, boolean forwardedFromCollection, VersionBucket bucket) throws IOException {
        try {
            BytesRef idBytes = cmd.getIndexedId();
            if (versionsStored) {
                long bucketVersion = bucket.highest;
                if (leaderLogic) {
                    if (forwardedFromCollection && ulog.getState() == UpdateLog.State.ACTIVE) {
                        // see SOLR-5308
                        if (log.isInfoEnabled()) {
                            log.info("Removing version field from doc: {}", cmd.getId());
                        }
                        versionOnUpdate = signedVersionOnUpdate = 0;
                    }
                    // leaders can also be in buffering state during "migrate" API call, see SOLR-5308
                    if (forwardedFromCollection && ulog.getState() != UpdateLog.State.ACTIVE && !isReplayOrPeersync) {
                        // we're not in an active state, and this update isn't from a replay, so buffer it.
                        if (log.isInfoEnabled()) {
                            log.info("Leader logic applied but update log is buffering: {}", cmd.getId());
                        }
                        cmd.setFlags(cmd.getFlags() | UpdateCommand.BUFFERING);
                        ulog.delete(cmd);
                        return true;
                    }
                    if (signedVersionOnUpdate != 0) {
                        Long lastVersion = vinfo.lookupVersion(cmd.getIndexedId());
                        long foundVersion = lastVersion == null ? -1 : lastVersion;
                        if ((signedVersionOnUpdate == foundVersion) || (signedVersionOnUpdate < 0 && foundVersion < 0) || (signedVersionOnUpdate == 1 && foundVersion > 0)) {
                        // we're ok if versions match, or if both are negative (all missing docs are equal), or if cmd
                        // specified it must exist (versionOnUpdate==1) and it does.
                        } else {
                            throw new SolrException(ErrorCode.CONFLICT, "version conflict for " + cmd.getId() + " expected=" + signedVersionOnUpdate + " actual=" + foundVersion);
                        }
                    }
                    long version = vinfo.getNewClock();
                    cmd.setVersion(-version);
                    bucket.updateHighest(version);
                } else {
                    cmd.setVersion(-versionOnUpdate);
                    if (ulog.getState() != UpdateLog.State.ACTIVE && isReplayOrPeersync == false) {
                        // we're not in an active state, and this update isn't from a replay, so buffer it.
                        cmd.setFlags(cmd.getFlags() | UpdateCommand.BUFFERING);
                        ulog.delete(cmd);
                        return true;
                    }
                    // if we aren't the leader, then we need to check that updates were not re-ordered
                    if (bucketVersion != 0 && bucketVersion < versionOnUpdate) {
                        // we're OK... this update has a version higher than anything we've seen
                        // in this bucket so far, so we know that no reordering has yet occurred.
                        bucket.updateHighest(versionOnUpdate);
                    } else {
                        // there have been updates higher than the current update. we need to check
                        // the specific version for this id.
                        Long lastVersion = vinfo.lookupVersion(cmd.getIndexedId());
                        if (lastVersion != null && Math.abs(lastVersion) >= versionOnUpdate) {
                            // This update is a repeat, or was reordered. We need to drop this update.
                            if (log.isDebugEnabled()) {
                                log.debug("Dropping delete update due to version {}", idBytes.utf8ToString());
                            }
                            return true;
                        }
                    }
                    if (!isSubShardLeader && replicaType == Replica.Type.TLOG && (cmd.getFlags() & UpdateCommand.REPLAY) == 0) {
                        cmd.setFlags(cmd.getFlags() | UpdateCommand.IGNORE_INDEXWRITER);
                    }
                }
            }
            doLocalDelete(cmd);
            return false;
        } finally {
            bucket.unlock();
        }
    }

    @Override
    public void processCommit(CommitUpdateCommand cmd) throws IOException {
        assert TestInjection.injectFailUpdateRequests();
        updateCommand = cmd;
        // replica type can only be NRT in standalone mode
        // NRT replicas will always commit
        doLocalCommit(cmd);
    }

    protected void doLocalCommit(CommitUpdateCommand cmd) throws IOException {
        if (vinfo != null) {
            long commitVersion = vinfo.getNewClock();
            cmd.setVersion(commitVersion);
            vinfo.lockForUpdate();
        }
        try {
            if (ulog == null || ulog.getState() == UpdateLog.State.ACTIVE || (cmd.getFlags() & UpdateCommand.REPLAY) != 0) {
                super.processCommit(cmd);
            } else {
                if (log.isInfoEnabled()) {
                    log.info("Ignoring commit while not ACTIVE - state: {} replay: {}", ulog.getState(), ((cmd.getFlags() & UpdateCommand.REPLAY) != 0));
                }
            }
        } finally {
            if (vinfo != null) {
                vinfo.unlockForUpdate();
            }
        }
    }

    @Override
    public final void finish() throws IOException {
        assert !finished : "lifecycle sanity check";
        finished = true;
        doDistribFinish();
        super.finish();
    }

    protected void doDistribFinish() throws IOException {
    // no-op for derived classes to implement
    }

    /**
     * {@link AddUpdateCommand#isNested} is set in {@link org.apache.solr.update.processor.NestedUpdateProcessorFactory},
     * which runs on leader and replicas just before run time processor
     *
     * @return whether this update changes a value of a nested document
     */
    private static boolean shouldRefreshUlogCaches(AddUpdateCommand cmd) {
        // which runs post-processor in the URP chain, having NestedURP set cmd#isNested.
        assert !cmd.getReq().getSchema().savesChildDocRelations() || cmd.isNested != null;
        // true if update adds children
        return Boolean.TRUE.equals(cmd.isNested);
    }

    /**
     * Returns a boolean indicating whether or not the caller should behave as
     * if this is the "leader" even when ZooKeeper is not enabled.
     * (Even in non zk mode, tests may simulate updates to/from a leader)
     */
    public static boolean getNonZkLeaderAssumption(SolrQueryRequest req) {
        DistribPhase phase = DistribPhase.parseParam(req.getParams().get(DISTRIB_UPDATE_PARAM));
        // definitely not the leader.  Otherwise assume we are.
        return DistribPhase.FROMLEADER != phase;
    }

    public static final class DistributedUpdatesAsyncException extends SolrException {

        public final List<Error> errors;

        public DistributedUpdatesAsyncException(List<Error> errors) {
            super(buildCode(errors), buildMsg(errors), null);
            this.errors = errors;
            // create a merged copy of the metadata from all wrapped exceptions
            NamedList<String> metadata = new NamedList<String>();
            for (Error error : errors) {
                if (error.e instanceof SolrException) {
                    SolrException e = (SolrException) error.e;
                    NamedList<String> eMeta = e.getMetadata();
                    if (null != eMeta) {
                        metadata.addAll(eMeta);
                    }
                }
            }
            if (0 < metadata.size()) {
                this.setMetadata(metadata);
            }
        }

        /**
         * Helper method for constructor
         */
        private static int buildCode(List<Error> errors) {
            assert null != errors;
            assert 0 < errors.size();
            int minCode = Integer.MAX_VALUE;
            int maxCode = Integer.MIN_VALUE;
            for (Error error : errors) {
                log.trace("REMOTE ERROR: {}", error);
                minCode = Math.min(error.statusCode, minCode);
                maxCode = Math.max(error.statusCode, maxCode);
            }
            if (minCode == maxCode) {
                // all codes are consistent, use that...
                return minCode;
            } else if (400 <= minCode && maxCode < 500) {
                // all codes are 4xx, use 400
                return ErrorCode.BAD_REQUEST.code;
            }
            // ...otherwise use sensible default
            return ErrorCode.SERVER_ERROR.code;
        }

        /**
         * Helper method for constructor
         */
        private static String buildMsg(List<Error> errors) {
            assert null != errors;
            assert 0 < errors.size();
            if (1 == errors.size()) {
                return "Async exception during distributed update: " + errors.get(0).e.getMessage();
            } else {
                StringBuilder buf = new StringBuilder(errors.size() + " Async exceptions during distributed update: ");
                for (Error error : errors) {
                    buf.append("\n");
                    buf.append(error.e.getMessage());
                }
                return buf.toString();
            }
        }
    }

    public static class RollupRequestReplicationTracker {

        private int achievedRf = Integer.MAX_VALUE;

        public int getAchievedRf() {
            return achievedRf;
        }

        // We want to report only the minimun _ever_ achieved...
        public void testAndSetAchievedRf(int rf) {
            this.achievedRf = Math.min(this.achievedRf, rf);
        }

        public String toString() {
            StringBuilder sb = new StringBuilder("RollupRequestReplicationTracker").append(" achievedRf: ").append(achievedRf);
            return sb.toString();
        }
    }

    public static class LeaderRequestReplicationTracker {

        private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

        // Since we only allocate one of these on the leader and, by definition, the leader has been found and is running,
        // we have a replication factor of one by default.
        private int achievedRf = 1;

        private final String myShardId;

        public LeaderRequestReplicationTracker(String shardId) {
            this.myShardId = shardId;
        }

        // gives the replication factor that was achieved for this request
        public int getAchievedRf() {
            return achievedRf;
        }

        public void trackRequestResult(Node node, boolean success) {
            if (log.isDebugEnabled()) {
                log.debug("trackRequestResult({}): success? {}, shardId={}", node, success, myShardId);
            }
            if (success) {
                ++achievedRf;
            }
        }

        public String toString() {
            StringBuilder sb = new StringBuilder("LeaderRequestReplicationTracker");
            sb.append(", achievedRf=").append(getAchievedRf()).append(" for shard ").append(myShardId);
            return sb.toString();
        }
    }
}
