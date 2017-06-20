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
import org.wso2.siddhi.core.event.stream.MetaStreamEvent;
import org.wso2.siddhi.core.exception.OperationNotSupportedException;
import org.wso2.siddhi.core.query.input.ProcessStreamReceiver;
import org.wso2.siddhi.core.query.input.stream.StreamRuntime;
import org.wso2.siddhi.core.util.parser.helper.ParameterWrapper;
import org.wso2.siddhi.core.util.statistics.LatencyTracker;
import org.wso2.siddhi.core.window.Window;
import org.wso2.siddhi.query.api.execution.query.input.stream.BasicSingleInputStream;
import org.wso2.siddhi.query.api.execution.query.input.stream.InputStream;
import org.wso2.siddhi.query.api.execution.query.input.stream.JoinInputStream;
import org.wso2.siddhi.query.api.execution.query.input.stream.SingleInputStream;
import org.wso2.siddhi.query.api.execution.query.input.stream.StateInputStream;

/**
 * Class to parse {@link InputStream}
 */
public class InputStreamParser {

    /**
     * Parse an InputStream returning corresponding StreamRuntime
     *
     * @param inputStream                input stream to be parsed
     * @param siddhiAppContext       associated siddhi siddhiAppContext
     * @param parameterWrapper
     * @param latencyTracker             latency tracker
     * @param outputExpectsExpiredEvents is output expects ExpiredEvents
     * @param queryName                  query name of input stream belongs to.
     * @return  StreamRuntime
     */
    public static StreamRuntime parse(InputStream inputStream, SiddhiAppContext siddhiAppContext,
                                      ParameterWrapper parameterWrapper,
                                      LatencyTracker latencyTracker, boolean outputExpectsExpiredEvents, String
                                              queryName) {

        if (inputStream instanceof BasicSingleInputStream || inputStream instanceof SingleInputStream) {
            SingleInputStream singleInputStream = (SingleInputStream) inputStream;
            Window window = parameterWrapper.getEventWindowMap().get(singleInputStream.getStreamId());
            boolean batchProcessingAllowed = window != null;      // If stream is from window, allow batch
            // processing
            ProcessStreamReceiver processStreamReceiver = new ProcessStreamReceiver(singleInputStream.getStreamId(),
                    latencyTracker, queryName);
            processStreamReceiver.setBatchProcessingAllowed(batchProcessingAllowed);
            return SingleInputStreamParser.parseInputStream((SingleInputStream) inputStream,
                    siddhiAppContext, parameterWrapper.copy().tableDefinitionMap(null),
                    new MetaStreamEvent(), processStreamReceiver, true, outputExpectsExpiredEvents, queryName);
        } else if (inputStream instanceof JoinInputStream) {
            return JoinInputStreamParser.parseInputStream(((JoinInputStream) inputStream), siddhiAppContext,
                    parameterWrapper, latencyTracker, outputExpectsExpiredEvents,
                    queryName);
        } else if (inputStream instanceof StateInputStream) {
            MetaStateEvent metaStateEvent = new MetaStateEvent(inputStream.getAllStreamIds().size());
            return StateInputStreamParser.parseInputStream(((StateInputStream) inputStream), siddhiAppContext,
                    metaStateEvent, parameterWrapper.copy().tableDefinitionMap(null).windowDefinitionMap(null),
                    latencyTracker,
                    queryName);
        } else {
            throw new OperationNotSupportedException();
        }
    }
}
