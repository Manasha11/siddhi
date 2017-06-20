/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.siddhi.core.util.parser;

import org.wso2.siddhi.core.config.SiddhiAppContext;
import org.wso2.siddhi.core.event.state.MetaStateEvent;
import org.wso2.siddhi.core.event.state.populater.StateEventPopulatorFactory;
import org.wso2.siddhi.core.event.stream.MetaStreamEvent;
import org.wso2.siddhi.core.exception.SiddhiAppCreationException;
import org.wso2.siddhi.core.executor.VariableExpressionExecutor;
import org.wso2.siddhi.core.query.QueryRuntime;
import org.wso2.siddhi.core.query.input.stream.StreamRuntime;
import org.wso2.siddhi.core.query.input.stream.join.JoinStreamRuntime;
import org.wso2.siddhi.core.query.input.stream.single.SingleStreamRuntime;
import org.wso2.siddhi.core.query.output.callback.OutputCallback;
import org.wso2.siddhi.core.query.output.ratelimit.OutputRateLimiter;
import org.wso2.siddhi.core.query.output.ratelimit.snapshot.WrappedSnapshotOutputRateLimiter;
import org.wso2.siddhi.core.query.selector.QuerySelector;
import org.wso2.siddhi.core.util.SiddhiConstants;
import org.wso2.siddhi.core.util.lock.LockSynchronizer;
import org.wso2.siddhi.core.util.lock.LockWrapper;
import org.wso2.siddhi.core.util.parser.helper.ParameterWrapper;
import org.wso2.siddhi.core.util.parser.helper.QueryParserHelper;
import org.wso2.siddhi.core.util.statistics.LatencyTracker;
import org.wso2.siddhi.query.api.annotation.Element;
import org.wso2.siddhi.query.api.exception.DuplicateDefinitionException;
import org.wso2.siddhi.query.api.execution.query.Query;
import org.wso2.siddhi.query.api.execution.query.input.handler.StreamHandler;
import org.wso2.siddhi.query.api.execution.query.input.stream.JoinInputStream;
import org.wso2.siddhi.query.api.execution.query.input.stream.SingleInputStream;
import org.wso2.siddhi.query.api.execution.query.output.stream.OutputStream;
import org.wso2.siddhi.query.api.util.AnnotationHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Class to parse {@link QueryRuntime}
 */
public class QueryParser {

    /**
     * Parse a query and return corresponding QueryRuntime.
     *
     * @param query                query to be parsed.
     * @param siddhiAppContext associated Siddhi app context.
     * @param parameterWrapper
     * @param lockSynchronizer     Lock synchronizer for sync the lock across queries.
     * @return queryRuntime
     */
    public static QueryRuntime parse(Query query, SiddhiAppContext siddhiAppContext,
                                     ParameterWrapper parameterWrapper,
                                     LockSynchronizer lockSynchronizer) {
        List<VariableExpressionExecutor> executors = new ArrayList<VariableExpressionExecutor>();
        parameterWrapper.variableExpressionExecutors(executors);
        QueryRuntime queryRuntime;
        Element nameElement = null;
        LatencyTracker latencyTracker = null;
        LockWrapper lockWrapper = null;
        try {
            nameElement = AnnotationHelper.getAnnotationElement("info", "name",
                    query.getAnnotations());
            String queryName = null;
            if (nameElement != null) {
                queryName = nameElement.getValue();
            } else {
                queryName = "query_" + UUID.randomUUID().toString();
            }
            if (siddhiAppContext.isStatsEnabled() && siddhiAppContext.getStatisticsManager() != null) {
                if (nameElement != null) {
                    String metricName =
                            siddhiAppContext.getSiddhiContext().getStatisticsConfiguration().getMatricPrefix() +
                                    SiddhiConstants.METRIC_DELIMITER + SiddhiConstants.METRIC_INFIX_EXECUTION_PLANS +
                                    SiddhiConstants.METRIC_DELIMITER + siddhiAppContext.getName() +
                                    SiddhiConstants.METRIC_DELIMITER + SiddhiConstants.METRIC_INFIX_SIDDHI +
                                    SiddhiConstants.METRIC_DELIMITER + SiddhiConstants.METRIC_INFIX_QUERIES +
                                    SiddhiConstants.METRIC_DELIMITER + queryName;
                    latencyTracker = siddhiAppContext.getSiddhiContext()
                            .getStatisticsConfiguration()
                            .getFactory()
                            .createLatencyTracker(metricName, siddhiAppContext.getStatisticsManager());
                }
            }
            OutputStream.OutputEventType outputEventType = query.getOutputStream().getOutputEventType();
            boolean outputExpectsExpiredEvents = false;
            if (outputEventType != OutputStream.OutputEventType.CURRENT_EVENTS) {
                outputExpectsExpiredEvents = true;
            }
            StreamRuntime streamRuntime = InputStreamParser.parse(query.getInputStream(),
                    siddhiAppContext, parameterWrapper,
                    latencyTracker,
                    outputExpectsExpiredEvents,
                    queryName);
            QuerySelector selector = SelectorParser.parse(query.getSelector(), query.getOutputStream(),
                    siddhiAppContext, streamRuntime.getMetaComplexEvent(), parameterWrapper,
                    queryName);
            boolean isWindow = query.getInputStream() instanceof JoinInputStream;
            if (!isWindow && query.getInputStream() instanceof SingleInputStream) {
                for (StreamHandler streamHandler : ((SingleInputStream) query.getInputStream()).getStreamHandlers()) {
                    if (streamHandler instanceof org.wso2.siddhi.query.api.execution.query.input.handler.Window) {
                        isWindow = true;
                        break;
                    }
                }
            }

            Element synchronizedElement = AnnotationHelper.getAnnotationElement("synchronized",
                    null, query.getAnnotations());
            if (synchronizedElement != null) {
                if (!("false".equalsIgnoreCase(synchronizedElement.getValue()))) {
                    lockWrapper = new LockWrapper(""); // Query LockWrapper does not need a unique
                    // id since it will
                    // not be passed to the LockSynchronizer.
                    lockWrapper.setLock(new ReentrantLock());   // LockWrapper does not have a default lock
                }
            } else {
                if (isWindow || !(streamRuntime instanceof SingleStreamRuntime)) {
                    if (streamRuntime instanceof JoinStreamRuntime) {
                        // If at least one Window is involved in the join, use the LockWrapper of that window
                        // for the query as well.
                        // If join is between two EventWindows, sync the locks of the LockWrapper of those windows
                        // and use either of them for query.
                        MetaStateEvent metaStateEvent = (MetaStateEvent) streamRuntime.getMetaComplexEvent();
                        MetaStreamEvent[] metaStreamEvents = metaStateEvent.getMetaStreamEvents();

                        if (metaStreamEvents[0].isWindowEvent() && metaStreamEvents[1].isWindowEvent()) {
                            LockWrapper leftLockWrapper = parameterWrapper.getEventWindowMap().get(metaStreamEvents[0]
                                    .getLastInputDefinition().getId()).getLock();
                            LockWrapper rightLockWrapper = parameterWrapper.getEventWindowMap().get(metaStreamEvents[1]
                                    .getLastInputDefinition().getId()).getLock();

                            if (!leftLockWrapper.equals(rightLockWrapper)) {
                                // Sync the lock across both wrappers
                                lockSynchronizer.sync(leftLockWrapper, rightLockWrapper);
                            }
                            // Can use either leftLockWrapper or rightLockWrapper since both of them will hold the
                            // same lock internally
                            // If either of their lock is updated later, the other lock also will be update by the
                            // LockSynchronizer.
                            lockWrapper = leftLockWrapper;
                        } else if (metaStreamEvents[0].isWindowEvent()) {
                            // Share the same wrapper as the query lock wrapper
                            lockWrapper = parameterWrapper.getEventWindowMap().get(metaStreamEvents[0]
                                    .getLastInputDefinition().getId())
                                    .getLock();
                        } else if (metaStreamEvents[1].isWindowEvent()) {
                            // Share the same wrapper as the query lock wrapper
                            lockWrapper = parameterWrapper.getEventWindowMap().get(metaStreamEvents[1].getLastInputDefinition().getId())
                                    .getLock();
                        } else {
                            // Join does not contain any Window
                            lockWrapper = new LockWrapper("");  // Query LockWrapper does not need a unique
                            // id since
                            // it will not be passed to the LockSynchronizer.
                            lockWrapper.setLock(new ReentrantLock());   // LockWrapper does not have a default lock
                        }

                    } else {
                        lockWrapper = new LockWrapper("");
                        lockWrapper.setLock(new ReentrantLock());
                    }
                }
            }

            OutputRateLimiter outputRateLimiter = OutputParser.constructOutputRateLimiter(query.getOutputStream()
                            .getId(),
                    query.getOutputRate(), query.getSelector().getGroupByList().size() != 0, isWindow,
                    siddhiAppContext.getScheduledExecutorService(), siddhiAppContext, queryName);
            if (outputRateLimiter instanceof WrappedSnapshotOutputRateLimiter) {
                selector.setBatchingEnabled(false);
            }
            siddhiAppContext.addEternalReferencedHolder(outputRateLimiter);

            OutputCallback outputCallback = OutputParser.constructOutputCallback(query.getOutputStream(),
                    streamRuntime.getMetaComplexEvent().getOutputStreamDefinition(), parameterWrapper,
                    siddhiAppContext, !
                            (streamRuntime instanceof SingleStreamRuntime), queryName);

            QueryParserHelper.reduceMetaComplexEvent(streamRuntime.getMetaComplexEvent());
            QueryParserHelper.updateVariablePosition(streamRuntime.getMetaComplexEvent(), executors);
            QueryParserHelper.initStreamRuntime(streamRuntime, streamRuntime.getMetaComplexEvent(), lockWrapper,
                    queryName);
            selector.setEventPopulator(StateEventPopulatorFactory.constructEventPopulator(streamRuntime
                    .getMetaComplexEvent()));
            queryRuntime = new QueryRuntime(query, siddhiAppContext, streamRuntime, selector, outputRateLimiter,
                    outputCallback, streamRuntime.getMetaComplexEvent(), lockWrapper != null);

            if (outputRateLimiter instanceof WrappedSnapshotOutputRateLimiter) {
                selector.setBatchingEnabled(false);
                ((WrappedSnapshotOutputRateLimiter) outputRateLimiter)
                        .init(streamRuntime.getMetaComplexEvent().getOutputStreamDefinition().getAttributeList().size(),
                                selector.getAttributeProcessorList(), streamRuntime.getMetaComplexEvent());
            }
            outputRateLimiter.init(siddhiAppContext, lockWrapper, queryName);

        } catch (DuplicateDefinitionException e) {
            if (nameElement != null) {
                throw new DuplicateDefinitionException(e.getMessage() + ", when creating query " + nameElement
                        .getValue(), e);
            } else {
                throw new DuplicateDefinitionException(e.getMessage(), e);
            }
        } catch (RuntimeException e) {
            if (nameElement != null) {
                throw new SiddhiAppCreationException(e.getMessage() + ", when creating query " + nameElement
                        .getValue(), e);
            } else {
                throw new SiddhiAppCreationException(e.getMessage(), e);
            }
        }
        return queryRuntime;
    }
}
