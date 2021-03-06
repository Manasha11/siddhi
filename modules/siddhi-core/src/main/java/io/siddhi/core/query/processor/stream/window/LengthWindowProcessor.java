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
package io.siddhi.core.query.processor.stream.window;

import io.siddhi.annotation.Example;
import io.siddhi.annotation.Extension;
import io.siddhi.annotation.Parameter;
import io.siddhi.annotation.util.DataType;
import io.siddhi.core.config.SiddhiQueryContext;
import io.siddhi.core.event.ComplexEvent;
import io.siddhi.core.event.ComplexEventChunk;
import io.siddhi.core.event.state.StateEvent;
import io.siddhi.core.event.stream.StreamEvent;
import io.siddhi.core.event.stream.StreamEventCloner;
import io.siddhi.core.event.stream.holder.SnapshotableStreamEventQueue;
import io.siddhi.core.executor.ConstantExpressionExecutor;
import io.siddhi.core.executor.ExpressionExecutor;
import io.siddhi.core.executor.VariableExpressionExecutor;
import io.siddhi.core.query.processor.Processor;
import io.siddhi.core.table.Table;
import io.siddhi.core.util.collection.operator.CompiledCondition;
import io.siddhi.core.util.collection.operator.MatchingMetaInfoHolder;
import io.siddhi.core.util.collection.operator.Operator;
import io.siddhi.core.util.config.ConfigReader;
import io.siddhi.core.util.parser.OperatorParser;
import io.siddhi.core.util.snapshot.state.SnapshotStateList;
import io.siddhi.query.api.exception.SiddhiAppValidationException;
import io.siddhi.query.api.expression.Expression;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementation of {@link WindowProcessor} which represent a Window operating based on a pre-defined length.
 */
@Extension(
        name = "length",
        namespace = "",
        description = "A sliding length window that holds the last 'window.length' events at a given time, " +
                "and gets updated for each arrival and expiry.",
        parameters = {
                @Parameter(name = "window.length",
                        description = "The number of events that should be included in a sliding length window.",
                        type = {DataType.INT})
        },
        examples = @Example(
                syntax = "define window StockEventWindow (symbol string, price float, volume int) " +
                        "length(10) output all events;\n\n" +
                        "@info(name = 'query0')\n" +
                        "from StockEventStream\n" +
                        "insert into StockEventWindow;\n" +
                        "@info(name = 'query1')\n\n" +
                        "from StockEventWindow\n" +
                        "select symbol, sum(price) as price\n" +
                        "insert all events into outputStream ;",
                description = "This will process last 10 events in a sliding manner."
        )
)
public class LengthWindowProcessor extends SlidingWindowProcessor implements FindableProcessor {

    private int length;
    private int count = 0;
    private SnapshotableStreamEventQueue expiredEventQueue;

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    @Override
    protected void init(ExpressionExecutor[] attributeExpressionExecutors, ConfigReader configReader,
                        SiddhiQueryContext siddhiQueryContext) {
        if (attributeExpressionExecutors.length == 1) {
            length = (Integer) ((ConstantExpressionExecutor) attributeExpressionExecutors[0]).getValue();
        } else {
            throw new SiddhiAppValidationException("Length window should only have one parameter (<int> " +
                    "window.length), but found " + attributeExpressionExecutors.length + " input parameters.");
        }
        expiredEventQueue = new SnapshotableStreamEventQueue(streamEventClonerHolder, length);
    }

    @Override
    protected void process(ComplexEventChunk<StreamEvent> streamEventChunk, Processor nextProcessor,
                           StreamEventCloner streamEventCloner) {
        synchronized (this) {
            long currentTime = siddhiQueryContext.getSiddhiAppContext().getTimestampGenerator().currentTime();
            while (streamEventChunk.hasNext()) {
                StreamEvent streamEvent = streamEventChunk.next();
                StreamEvent clonedEvent = streamEventCloner.copyStreamEvent(streamEvent);
                clonedEvent.setType(StreamEvent.Type.EXPIRED);
                if (count < length) {
                    count++;
                    this.expiredEventQueue.add(clonedEvent);
                } else {
                    StreamEvent firstEvent = this.expiredEventQueue.poll();
                    if (firstEvent != null) {
                        firstEvent.setTimestamp(currentTime);
                        streamEventChunk.insertBeforeCurrent(firstEvent);
                        this.expiredEventQueue.add(clonedEvent);
                    } else {
                        StreamEvent resetEvent = streamEventCloner.copyStreamEvent(streamEvent);
                        resetEvent.setType(ComplexEvent.Type.RESET);
                        // adding resetEvent and clonedEvent event to the streamEventChunk
                        // since we are using insertAfterCurrent(), the final order will be
                        // currentEvent > clonedEvent (or expiredEvent) > resetEvent
                        streamEventChunk.insertAfterCurrent(resetEvent);
                        streamEventChunk.insertAfterCurrent(clonedEvent);

                        // since we manually added resetEvent and clonedEvent in earlier step
                        // we have to skip those two events from getting processed in the next
                        // iteration. Hence, calling next() twice.
                        streamEventChunk.next();
                        streamEventChunk.next();
                    }
                }
            }
        }
        nextProcessor.process(streamEventChunk);
    }

    @Override
    public synchronized StreamEvent find(StateEvent matchingEvent, CompiledCondition compiledCondition) {
        return ((Operator) compiledCondition).find(matchingEvent, expiredEventQueue, streamEventCloner);
    }

    @Override
    public CompiledCondition compileCondition(Expression condition, MatchingMetaInfoHolder matchingMetaInfoHolder,
                                              List<VariableExpressionExecutor> variableExpressionExecutors,
                                              Map<String, Table> tableMap, SiddhiQueryContext siddhiQueryContext) {
        return OperatorParser.constructOperator(expiredEventQueue, condition, matchingMetaInfoHolder,
                variableExpressionExecutors, tableMap, siddhiQueryContext);
    }

    @Override
    public void start() {
        //Do nothing
    }

    @Override
    public void stop() {
        //Do nothing
    }


    @Override
    public Map<String, Object> currentState() {
        Map<String, Object> state = new HashMap<>();
        synchronized (this) {
            state.put("Count", count);
            state.put("ExpiredEventQueue", expiredEventQueue.getSnapshot());
        }
        return state;
    }


    @Override
    public synchronized void restoreState(Map<String, Object> state) {
        count = (int) state.get("Count");
        expiredEventQueue.clear();
        expiredEventQueue.restore((SnapshotStateList) state.get("ExpiredEventQueue"));
    }
}
