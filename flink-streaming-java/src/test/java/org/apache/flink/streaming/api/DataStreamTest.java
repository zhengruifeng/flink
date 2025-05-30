/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api;

import org.apache.flink.api.common.InvalidProgramException;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.Function;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.Partitioner;
import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.common.operators.ResourceSpec;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.BasicArrayTypeInfo;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.common.typeinfo.PrimitiveArrayTypeInfo;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.EnumTypeInfo;
import org.apache.flink.api.java.typeutils.GenericTypeInfo;
import org.apache.flink.api.java.typeutils.ObjectArrayTypeInfo;
import org.apache.flink.api.java.typeutils.TupleTypeInfo;
import org.apache.flink.api.java.typeutils.TypeExtractor;
import org.apache.flink.runtime.asyncprocessing.operators.AbstractAsyncStateUdfStreamOperator;
import org.apache.flink.runtime.asyncprocessing.operators.AsyncKeyedProcessOperator;
import org.apache.flink.streaming.api.datastream.BroadcastConnectedStream;
import org.apache.flink.streaming.api.datastream.BroadcastStream;
import org.apache.flink.streaming.api.datastream.ConnectedStreams;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSink;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.api.functions.co.BroadcastProcessFunction;
import org.apache.flink.streaming.api.functions.co.CoFlatMapFunction;
import org.apache.flink.streaming.api.functions.co.CoMapFunction;
import org.apache.flink.streaming.api.functions.co.KeyedBroadcastProcessFunction;
import org.apache.flink.streaming.api.functions.sink.legacy.SinkFunction;
import org.apache.flink.streaming.api.functions.sink.v2.DiscardingSink;
import org.apache.flink.streaming.api.functions.windowing.AllWindowFunction;
import org.apache.flink.streaming.api.graph.StreamEdge;
import org.apache.flink.streaming.api.graph.StreamGraph;
import org.apache.flink.streaming.api.operators.AbstractUdfStreamOperator;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.api.operators.ProcessOperator;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.api.windowing.assigners.GlobalWindows;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.triggers.CountTrigger;
import org.apache.flink.streaming.api.windowing.triggers.PurgingTrigger;
import org.apache.flink.streaming.api.windowing.windows.GlobalWindow;
import org.apache.flink.streaming.runtime.operators.util.WatermarkStrategyWithPunctuatedWatermarks;
import org.apache.flink.streaming.runtime.partitioner.BroadcastPartitioner;
import org.apache.flink.streaming.runtime.partitioner.CustomPartitionerWrapper;
import org.apache.flink.streaming.runtime.partitioner.ForwardPartitioner;
import org.apache.flink.streaming.runtime.partitioner.GlobalPartitioner;
import org.apache.flink.streaming.runtime.partitioner.KeyGroupStreamPartitioner;
import org.apache.flink.streaming.runtime.partitioner.RebalancePartitioner;
import org.apache.flink.streaming.runtime.partitioner.ShufflePartitioner;
import org.apache.flink.streaming.runtime.partitioner.StreamPartitioner;
import org.apache.flink.util.Collector;

import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/** Tests for {@link DataStream}. */
@SuppressWarnings("serial")
class DataStreamTest {

    /** Ensure that WatermarkStrategy is easy to use in the API, without superfluous generics. */
    @Test
    void testErgonomicWatermarkStrategy() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<String> input = env.fromData("bonjour");

        // as soon as you have a chain of methods the first call needs a generic
        input.assignTimestampsAndWatermarks(
                WatermarkStrategy.forBoundedOutOfOrderness(Duration.ofMillis(10)));

        // as soon as you have a chain of methods the first call needs to specify the generic type
        input.assignTimestampsAndWatermarks(
                WatermarkStrategy.<String>forBoundedOutOfOrderness(Duration.ofMillis(10))
                        .withTimestampAssigner((event, timestamp) -> 42L));
    }

    /**
     * Tests union functionality. This ensures that self-unions and unions of streams with differing
     * parallelism work.
     *
     * @throws Exception
     */
    @Test
    void testUnion() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(4);

        DataStream<Long> input1 =
                env.fromSequence(0, 0)
                        .map(
                                new MapFunction<Long, Long>() {
                                    @Override
                                    public Long map(Long value) throws Exception {
                                        return null;
                                    }
                                });

        DataStream<Long> selfUnion =
                input1.union(input1)
                        .map(
                                new MapFunction<Long, Long>() {
                                    @Override
                                    public Long map(Long value) throws Exception {
                                        return null;
                                    }
                                });

        DataStream<Long> input6 =
                env.fromSequence(0, 0)
                        .map(
                                new MapFunction<Long, Long>() {
                                    @Override
                                    public Long map(Long value) throws Exception {
                                        return null;
                                    }
                                });

        DataStream<Long> selfUnionDifferentPartition =
                input6.broadcast()
                        .union(input6)
                        .map(
                                new MapFunction<Long, Long>() {
                                    @Override
                                    public Long map(Long value) throws Exception {
                                        return null;
                                    }
                                });

        DataStream<Long> input2 =
                env.fromSequence(0, 0)
                        .map(
                                new MapFunction<Long, Long>() {
                                    @Override
                                    public Long map(Long value) throws Exception {
                                        return null;
                                    }
                                })
                        .setParallelism(4);

        DataStream<Long> input3 =
                env.fromSequence(0, 0)
                        .map(
                                new MapFunction<Long, Long>() {
                                    @Override
                                    public Long map(Long value) throws Exception {
                                        return null;
                                    }
                                })
                        .setParallelism(2);

        DataStream<Long> unionDifferingParallelism =
                input2.union(input3)
                        .map(
                                new MapFunction<Long, Long>() {
                                    @Override
                                    public Long map(Long value) throws Exception {
                                        return null;
                                    }
                                })
                        .setParallelism(4);

        DataStream<Long> input4 =
                env.fromSequence(0, 0)
                        .map(
                                new MapFunction<Long, Long>() {
                                    @Override
                                    public Long map(Long value) throws Exception {
                                        return null;
                                    }
                                })
                        .setParallelism(2);

        DataStream<Long> input5 =
                env.fromSequence(0, 0)
                        .map(
                                new MapFunction<Long, Long>() {
                                    @Override
                                    public Long map(Long value) throws Exception {
                                        return null;
                                    }
                                })
                        .setParallelism(4);

        DataStream<Long> unionDifferingPartitioning =
                input4.broadcast()
                        .union(input5)
                        .map(
                                new MapFunction<Long, Long>() {
                                    @Override
                                    public Long map(Long value) throws Exception {
                                        return null;
                                    }
                                })
                        .setParallelism(4);

        StreamGraph streamGraph = getStreamGraph(env);

        // verify self union
        assertThat(streamGraph.getStreamNode(selfUnion.getId()).getInEdges()).hasSize(2);
        for (StreamEdge edge : streamGraph.getStreamNode(selfUnion.getId()).getInEdges()) {
            assertThat(edge.getPartitioner()).isInstanceOf(ForwardPartitioner.class);
        }

        // verify self union with different partitioners
        assertThat(streamGraph.getStreamNode(selfUnionDifferentPartition.getId()).getInEdges())
                .hasSize(2);
        boolean hasForward = false;
        boolean hasBroadcast = false;
        for (StreamEdge edge :
                streamGraph.getStreamNode(selfUnionDifferentPartition.getId()).getInEdges()) {
            if (edge.getPartitioner() instanceof ForwardPartitioner) {
                hasForward = true;
            }
            if (edge.getPartitioner() instanceof BroadcastPartitioner) {
                hasBroadcast = true;
            }
        }
        assertThat(hasForward && hasBroadcast).isTrue();

        // verify union of streams with differing parallelism
        assertThat(streamGraph.getStreamNode(unionDifferingParallelism.getId()).getInEdges())
                .hasSize(2);
        for (StreamEdge edge :
                streamGraph.getStreamNode(unionDifferingParallelism.getId()).getInEdges()) {
            if (edge.getSourceId() == input2.getId()) {
                assertThat(edge.getPartitioner()).isInstanceOf(ForwardPartitioner.class);
            } else if (edge.getSourceId() == input3.getId()) {
                assertThat(edge.getPartitioner()).isInstanceOf(RebalancePartitioner.class);
            } else {
                fail("Wrong input edge.");
            }
        }

        // verify union of streams with differing partitionings
        assertThat(streamGraph.getStreamNode(unionDifferingPartitioning.getId()).getInEdges())
                .hasSize(2);
        for (StreamEdge edge :
                streamGraph.getStreamNode(unionDifferingPartitioning.getId()).getInEdges()) {
            if (edge.getSourceId() == input4.getId()) {
                assertThat(edge.getPartitioner()).isInstanceOf(BroadcastPartitioner.class);
            } else if (edge.getSourceId() == input5.getId()) {
                assertThat(edge.getPartitioner()).isInstanceOf(ForwardPartitioner.class);
            } else {
                fail("Wrong input edge.");
            }
        }
    }

    /**
     * Tests {@link SingleOutputStreamOperator#name(String)} functionality.
     *
     * @throws Exception
     */
    @Test
    void testNaming() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<Long> dataStream1 =
                env.fromSequence(0, 0)
                        .name("testSource1")
                        .map(
                                new MapFunction<Long, Long>() {
                                    @Override
                                    public Long map(Long value) throws Exception {
                                        return null;
                                    }
                                })
                        .name("testMap");

        DataStream<Long> dataStream2 =
                env.fromSequence(0, 0)
                        .name("testSource2")
                        .map(
                                new MapFunction<Long, Long>() {
                                    @Override
                                    public Long map(Long value) throws Exception {
                                        return null;
                                    }
                                })
                        .name("testMap");

        dataStream1
                .connect(dataStream2)
                .flatMap(
                        new CoFlatMapFunction<Long, Long, Long>() {

                            @Override
                            public void flatMap1(Long value, Collector<Long> out)
                                    throws Exception {}

                            @Override
                            public void flatMap2(Long value, Collector<Long> out)
                                    throws Exception {}
                        })
                .name("testCoFlatMap")
                .windowAll(GlobalWindows.create())
                .trigger(PurgingTrigger.of(CountTrigger.of(10)))
                .reduce(
                        new ReduceFunction<Long>() {
                            private static final long serialVersionUID = 1L;

                            @Override
                            public Long reduce(Long value1, Long value2) throws Exception {
                                return null;
                            }
                        })
                .name("testWindowReduce")
                .print();

        // test functionality through the operator names in the execution plan
        String plan = env.getExecutionPlan();

        assertThat(plan).contains("testSource1");
        assertThat(plan).contains("testSource2");
        assertThat(plan).contains("testMap");
        assertThat(plan).contains("testMap");
        assertThat(plan).contains("testCoFlatMap");
        assertThat(plan).contains("testWindowReduce");
    }

    /**
     * Tests that {@link DataStream#keyBy(KeySelector)} and {@link
     * DataStream#partitionCustom(Partitioner, KeySelector)} result in different and correct
     * topologies. Does the some for the {@link ConnectedStreams}.
     */
    @Test
    void testPartitioning() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<Tuple2<Long, Long>> src1 = env.fromData(new Tuple2<>(0L, 0L));
        DataStream<Tuple2<Long, Long>> src2 = env.fromData(new Tuple2<>(0L, 0L));
        ConnectedStreams<Tuple2<Long, Long>, Tuple2<Long, Long>> connected = src1.connect(src2);

        // Testing DataStream grouping
        DataStream<Tuple2<Long, Long>> group1 = src1.keyBy(x -> x.f0);
        DataStream<Tuple2<Long, Long>> group2 =
                src1.keyBy(x -> Tuple2.of(x.f1, x.f0), Types.TUPLE(Types.LONG, Types.LONG));
        DataStream<Tuple2<Long, Long>> group3 = src1.keyBy(new FirstSelector());

        int id1 = createDownStreamId(group1);
        int id2 = createDownStreamId(group2);
        int id3 = createDownStreamId(group3);

        assertThat(isPartitioned(getStreamGraph(env).getStreamEdgesOrThrow(src1.getId(), id1)))
                .isTrue();
        assertThat(isPartitioned(getStreamGraph(env).getStreamEdgesOrThrow(src1.getId(), id2)))
                .isTrue();
        assertThat(isPartitioned(getStreamGraph(env).getStreamEdgesOrThrow(src1.getId(), id3)))
                .isTrue();

        assertThat(isKeyed(group1)).isTrue();
        assertThat(isKeyed(group2)).isTrue();
        assertThat(isKeyed(group3)).isTrue();

        // Testing DataStream partitioning
        DataStream<Tuple2<Long, Long>> partition1 = src1.keyBy(x -> x.f0);
        DataStream<Tuple2<Long, Long>> partition2 =
                src1.keyBy(x -> Tuple2.of(x.f1, x.f0), Types.TUPLE(Types.LONG, Types.LONG));
        DataStream<Tuple2<Long, Long>> partition3 = src1.keyBy(new FirstSelector());

        int pid1 = createDownStreamId(partition1);
        int pid2 = createDownStreamId(partition2);
        int pid3 = createDownStreamId(partition3);

        assertThat(isPartitioned(getStreamGraph(env).getStreamEdgesOrThrow(src1.getId(), pid1)))
                .isTrue();
        assertThat(isPartitioned(getStreamGraph(env).getStreamEdgesOrThrow(src1.getId(), pid2)))
                .isTrue();
        assertThat(isPartitioned(getStreamGraph(env).getStreamEdgesOrThrow(src1.getId(), pid3)))
                .isTrue();

        assertThat(isKeyed(partition1)).isTrue();
        assertThat(isKeyed(partition2)).isTrue();
        assertThat(isKeyed(partition3)).isTrue();

        // Testing DataStream custom partitioning
        Partitioner<Long> longPartitioner =
                new Partitioner<Long>() {
                    @Override
                    public int partition(Long key, int numPartitions) {
                        return 100;
                    }
                };

        DataStream<Tuple2<Long, Long>> customPartition1 =
                src1.partitionCustom(longPartitioner, x -> x.f0);
        DataStream<Tuple2<Long, Long>> customPartition3 =
                src1.partitionCustom(longPartitioner, x -> x.f0);
        DataStream<Tuple2<Long, Long>> customPartition4 =
                src1.partitionCustom(longPartitioner, new FirstSelector());

        int cid1 = createDownStreamId(customPartition1);
        int cid2 = createDownStreamId(customPartition3);
        int cid3 = createDownStreamId(customPartition4);

        assertThat(
                        isCustomPartitioned(
                                getStreamGraph(env).getStreamEdgesOrThrow(src1.getId(), cid1)))
                .isTrue();
        assertThat(
                        isCustomPartitioned(
                                getStreamGraph(env).getStreamEdgesOrThrow(src1.getId(), cid2)))
                .isTrue();
        assertThat(
                        isCustomPartitioned(
                                getStreamGraph(env).getStreamEdgesOrThrow(src1.getId(), cid3)))
                .isTrue();

        assertThat(isKeyed(customPartition1)).isFalse();
        assertThat(isKeyed(customPartition3)).isFalse();
        assertThat(isKeyed(customPartition4)).isFalse();

        // Testing ConnectedStreams grouping
        ConnectedStreams<Tuple2<Long, Long>, Tuple2<Long, Long>> connectedGroup1 =
                connected.keyBy(0, 0);
        Integer downStreamId1 = createDownStreamId(connectedGroup1);

        ConnectedStreams<Tuple2<Long, Long>, Tuple2<Long, Long>> connectedGroup2 =
                connected.keyBy(new int[] {0}, new int[] {0});
        Integer downStreamId2 = createDownStreamId(connectedGroup2);

        ConnectedStreams<Tuple2<Long, Long>, Tuple2<Long, Long>> connectedGroup3 =
                connected.keyBy("f0", "f0");
        Integer downStreamId3 = createDownStreamId(connectedGroup3);

        ConnectedStreams<Tuple2<Long, Long>, Tuple2<Long, Long>> connectedGroup4 =
                connected.keyBy(new String[] {"f0"}, new String[] {"f0"});
        Integer downStreamId4 = createDownStreamId(connectedGroup4);

        ConnectedStreams<Tuple2<Long, Long>, Tuple2<Long, Long>> connectedGroup5 =
                connected.keyBy(new FirstSelector(), new FirstSelector());
        Integer downStreamId5 = createDownStreamId(connectedGroup5);

        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src1.getId(), downStreamId1)))
                .isTrue();
        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src2.getId(), downStreamId1)))
                .isTrue();

        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src1.getId(), downStreamId2)))
                .isTrue();
        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src2.getId(), downStreamId2)))
                .isTrue();

        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src1.getId(), downStreamId3)))
                .isTrue();
        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src2.getId(), downStreamId3)))
                .isTrue();

        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src1.getId(), downStreamId4)))
                .isTrue();
        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src2.getId(), downStreamId4)))
                .isTrue();

        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src1.getId(), downStreamId5)))
                .isTrue();
        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src2.getId(), downStreamId5)))
                .isTrue();

        assertThat(isKeyed(connectedGroup1)).isTrue();
        assertThat(isKeyed(connectedGroup2)).isTrue();
        assertThat(isKeyed(connectedGroup3)).isTrue();
        assertThat(isKeyed(connectedGroup4)).isTrue();
        assertThat(isKeyed(connectedGroup5)).isTrue();

        // Testing ConnectedStreams partitioning
        ConnectedStreams<Tuple2<Long, Long>, Tuple2<Long, Long>> connectedPartition1 =
                connected.keyBy(0, 0);
        Integer connectDownStreamId1 = createDownStreamId(connectedPartition1);

        ConnectedStreams<Tuple2<Long, Long>, Tuple2<Long, Long>> connectedPartition2 =
                connected.keyBy(new int[] {0}, new int[] {0});
        Integer connectDownStreamId2 = createDownStreamId(connectedPartition2);

        ConnectedStreams<Tuple2<Long, Long>, Tuple2<Long, Long>> connectedPartition3 =
                connected.keyBy("f0", "f0");
        Integer connectDownStreamId3 = createDownStreamId(connectedPartition3);

        ConnectedStreams<Tuple2<Long, Long>, Tuple2<Long, Long>> connectedPartition4 =
                connected.keyBy(new String[] {"f0"}, new String[] {"f0"});
        Integer connectDownStreamId4 = createDownStreamId(connectedPartition4);

        ConnectedStreams<Tuple2<Long, Long>, Tuple2<Long, Long>> connectedPartition5 =
                connected.keyBy(new FirstSelector(), new FirstSelector());
        Integer connectDownStreamId5 = createDownStreamId(connectedPartition5);

        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src1.getId(), connectDownStreamId1)))
                .isTrue();
        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src2.getId(), connectDownStreamId1)))
                .isTrue();

        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src1.getId(), connectDownStreamId2)))
                .isTrue();
        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src2.getId(), connectDownStreamId2)))
                .isTrue();

        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src1.getId(), connectDownStreamId3)))
                .isTrue();
        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src2.getId(), connectDownStreamId3)))
                .isTrue();

        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src1.getId(), connectDownStreamId4)))
                .isTrue();
        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src2.getId(), connectDownStreamId4)))
                .isTrue();

        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src1.getId(), connectDownStreamId5)))
                .isTrue();
        assertThat(
                        isPartitioned(
                                getStreamGraph(env)
                                        .getStreamEdgesOrThrow(src2.getId(), connectDownStreamId5)))
                .isTrue();

        assertThat(isKeyed(connectedPartition1)).isTrue();
        assertThat(isKeyed(connectedPartition2)).isTrue();
        assertThat(isKeyed(connectedPartition3)).isTrue();
        assertThat(isKeyed(connectedPartition4)).isTrue();
        assertThat(isKeyed(connectedPartition5)).isTrue();
    }

    /** Tests whether parallelism gets set. */
    @Test
    void testParallelism() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStreamSource<Tuple2<Long, Long>> src = env.fromData(new Tuple2<>(0L, 0L));
        env.setParallelism(10);

        SingleOutputStreamOperator<Long> map =
                src.map(
                                new MapFunction<Tuple2<Long, Long>, Long>() {
                                    @Override
                                    public Long map(Tuple2<Long, Long> value) throws Exception {
                                        return null;
                                    }
                                })
                        .name("MyMap");

        DataStream<Long> windowed =
                map.windowAll(GlobalWindows.create())
                        .trigger(PurgingTrigger.of(CountTrigger.of(10)))
                        .reduce(
                                new ReduceFunction<Long>() {
                                    @Override
                                    public Long reduce(Long value1, Long value2) throws Exception {
                                        return null;
                                    }
                                });

        windowed.sinkTo(new DiscardingSink<Long>());

        DataStreamSink<Long> sink =
                map.addSink(
                        new SinkFunction<Long>() {
                            private static final long serialVersionUID = 1L;

                            @Override
                            public void invoke(Long value) throws Exception {}
                        });

        assertThat(getStreamGraph(env).getStreamNode(src.getId()).getParallelism()).isOne();
        assertThat(getStreamGraph(env).getStreamNode(map.getId()).getParallelism()).isEqualTo(10);
        assertThat(getStreamGraph(env).getStreamNode(windowed.getId()).getParallelism()).isOne();
        assertThat(
                        getStreamGraph(env)
                                .getStreamNode(sink.getTransformation().getId())
                                .getParallelism())
                .isEqualTo(10);

        env.setParallelism(7);

        // Some parts, such as windowing rely on the fact that previous operators have a parallelism
        // set when instantiating the Discretizer. This would break if we dynamically changed
        // the parallelism of operations when changing the setting on the Execution Environment.
        assertThat(getStreamGraph(env).getStreamNode(src.getId()).getParallelism()).isOne();
        assertThat(getStreamGraph(env).getStreamNode(map.getId()).getParallelism()).isEqualTo(10);
        assertThat(getStreamGraph(env).getStreamNode(windowed.getId()).getParallelism()).isOne();
        assertThat(
                        getStreamGraph(env)
                                .getStreamNode(sink.getTransformation().getId())
                                .getParallelism())
                .isEqualTo(10);

        DataStreamSource<Long> parallelSource = env.fromSequence(0, 0);
        parallelSource.sinkTo(new DiscardingSink<Long>());
        assertThat(getStreamGraph(env).getStreamNode(parallelSource.getId()).getParallelism())
                .isEqualTo(7);

        parallelSource.setParallelism(3);
        assertThat(getStreamGraph(env).getStreamNode(parallelSource.getId()).getParallelism())
                .isEqualTo(3);

        map.setParallelism(2);
        assertThat(getStreamGraph(env).getStreamNode(map.getId()).getParallelism()).isEqualTo(2);

        sink.setParallelism(4);
        assertThat(
                        getStreamGraph(env)
                                .getStreamNode(sink.getTransformation().getId())
                                .getParallelism())
                .isEqualTo(4);
    }

    /** Tests whether resources get set. */
    @Test
    void testResources() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        ResourceSpec minResource1 = ResourceSpec.newBuilder(1.0, 100).build();
        ResourceSpec preferredResource1 = ResourceSpec.newBuilder(2.0, 200).build();

        ResourceSpec minResource2 = ResourceSpec.newBuilder(1.0, 200).build();
        ResourceSpec preferredResource2 = ResourceSpec.newBuilder(2.0, 300).build();

        ResourceSpec minResource3 = ResourceSpec.newBuilder(1.0, 300).build();
        ResourceSpec preferredResource3 = ResourceSpec.newBuilder(2.0, 400).build();

        ResourceSpec minResource4 = ResourceSpec.newBuilder(1.0, 400).build();
        ResourceSpec preferredResource4 = ResourceSpec.newBuilder(2.0, 500).build();

        ResourceSpec minResource5 = ResourceSpec.newBuilder(1.0, 500).build();
        ResourceSpec preferredResource5 = ResourceSpec.newBuilder(2.0, 600).build();

        ResourceSpec minResource6 = ResourceSpec.newBuilder(1.0, 600).build();
        ResourceSpec preferredResource6 = ResourceSpec.newBuilder(2.0, 700).build();

        ResourceSpec minResource7 = ResourceSpec.newBuilder(1.0, 700).build();
        ResourceSpec preferredResource7 = ResourceSpec.newBuilder(2.0, 800).build();

        Method opMethod =
                SingleOutputStreamOperator.class.getDeclaredMethod(
                        "setResources", ResourceSpec.class, ResourceSpec.class);
        opMethod.setAccessible(true);

        Method sinkMethod =
                DataStreamSink.class.getDeclaredMethod(
                        "setResources", ResourceSpec.class, ResourceSpec.class);
        sinkMethod.setAccessible(true);

        DataStream<Long> source1 = env.fromSequence(0, 0);
        opMethod.invoke(source1, minResource1, preferredResource1);

        DataStream<Long> map1 =
                source1.map(
                        new MapFunction<Long, Long>() {
                            @Override
                            public Long map(Long value) throws Exception {
                                return null;
                            }
                        });
        opMethod.invoke(map1, minResource2, preferredResource2);

        DataStream<Long> source2 = env.fromSequence(0, 0);
        opMethod.invoke(source2, minResource3, preferredResource3);

        DataStream<Long> map2 =
                source2.map(
                        new MapFunction<Long, Long>() {
                            @Override
                            public Long map(Long value) throws Exception {
                                return null;
                            }
                        });
        opMethod.invoke(map2, minResource4, preferredResource4);

        DataStream<Long> connected =
                map1.connect(map2)
                        .flatMap(
                                new CoFlatMapFunction<Long, Long, Long>() {
                                    @Override
                                    public void flatMap1(Long value, Collector<Long> out)
                                            throws Exception {}

                                    @Override
                                    public void flatMap2(Long value, Collector<Long> out)
                                            throws Exception {}
                                });
        opMethod.invoke(connected, minResource5, preferredResource5);

        DataStream<Long> windowed =
                connected
                        .windowAll(GlobalWindows.create())
                        .trigger(PurgingTrigger.of(CountTrigger.of(10)))
                        .reduce(
                                new ReduceFunction<Long>() {
                                    private static final long serialVersionUID = 1L;

                                    @Override
                                    public Long reduce(Long value1, Long value2) throws Exception {
                                        return null;
                                    }
                                });
        opMethod.invoke(windowed, minResource6, preferredResource6);

        DataStreamSink<Long> sink = windowed.print();
        sinkMethod.invoke(sink, minResource7, preferredResource7);

        assertThat(getStreamGraph(env).getStreamNode(source1.getId()).getMinResources())
                .isEqualTo(minResource1);
        assertThat(getStreamGraph(env).getStreamNode(source1.getId()).getPreferredResources())
                .isEqualTo(preferredResource1);

        assertThat(getStreamGraph(env).getStreamNode(map1.getId()).getMinResources())
                .isEqualTo(minResource2);
        assertThat(getStreamGraph(env).getStreamNode(map1.getId()).getPreferredResources())
                .isEqualTo(preferredResource2);

        assertThat(getStreamGraph(env).getStreamNode(source2.getId()).getMinResources())
                .isEqualTo(minResource3);
        assertThat(getStreamGraph(env).getStreamNode(source2.getId()).getPreferredResources())
                .isEqualTo(preferredResource3);

        assertThat(getStreamGraph(env).getStreamNode(map2.getId()).getMinResources())
                .isEqualTo(minResource4);
        assertThat(getStreamGraph(env).getStreamNode(map2.getId()).getPreferredResources())
                .isEqualTo(preferredResource4);

        assertThat(getStreamGraph(env).getStreamNode(connected.getId()).getMinResources())
                .isEqualTo(minResource5);
        assertThat(getStreamGraph(env).getStreamNode(connected.getId()).getPreferredResources())
                .isEqualTo(preferredResource5);

        assertThat(getStreamGraph(env).getStreamNode(windowed.getId()).getMinResources())
                .isEqualTo(minResource6);
        assertThat(getStreamGraph(env).getStreamNode(windowed.getId()).getPreferredResources())
                .isEqualTo(preferredResource6);

        assertThat(
                        getStreamGraph(env)
                                .getStreamNode(sink.getTransformation().getId())
                                .getMinResources())
                .isEqualTo(minResource7);
        assertThat(
                        getStreamGraph(env)
                                .getStreamNode(sink.getTransformation().getId())
                                .getPreferredResources())
                .isEqualTo(preferredResource7);
    }

    @Test
    void testTypeInfo() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<Long> src1 = env.fromSequence(0, 0);
        assertThat(src1.getType()).isEqualTo(TypeExtractor.getForClass(Long.class));

        DataStream<Tuple2<Integer, String>> map =
                src1.map(
                        new MapFunction<Long, Tuple2<Integer, String>>() {
                            @Override
                            public Tuple2<Integer, String> map(Long value) throws Exception {
                                return null;
                            }
                        });

        assertThat(map.getType()).isEqualTo(TypeExtractor.getForObject(new Tuple2<>(0, "")));

        DataStream<String> window =
                map.windowAll(GlobalWindows.create())
                        .trigger(PurgingTrigger.of(CountTrigger.of(5)))
                        .apply(
                                new AllWindowFunction<
                                        Tuple2<Integer, String>, String, GlobalWindow>() {
                                    @Override
                                    public void apply(
                                            GlobalWindow window,
                                            Iterable<Tuple2<Integer, String>> values,
                                            Collector<String> out)
                                            throws Exception {}
                                });

        assertThat(window.getType()).isEqualTo(TypeExtractor.getForClass(String.class));

        DataStream<CustomPOJO> flatten =
                window.windowAll(GlobalWindows.create())
                        .trigger(PurgingTrigger.of(CountTrigger.of(5)))
                        .aggregate(
                                new AggregateFunction<String, CustomPOJO, CustomPOJO>() {
                                    private static final long serialVersionUID = 1L;

                                    @Override
                                    public CustomPOJO createAccumulator() {
                                        return null;
                                    }

                                    @Override
                                    public CustomPOJO add(String value, CustomPOJO accumulator) {
                                        return null;
                                    }

                                    @Override
                                    public CustomPOJO getResult(CustomPOJO accumulator) {
                                        return null;
                                    }

                                    @Override
                                    public CustomPOJO merge(CustomPOJO a, CustomPOJO b) {
                                        return null;
                                    }
                                });

        assertThat(flatten.getType()).isEqualTo(TypeExtractor.getForClass(CustomPOJO.class));
    }

    /**
     * Verify that a {@link KeyedStream#process(KeyedProcessFunction)} call is correctly translated
     * to an operator.
     */
    @Test
    void testKeyedStreamKeyedProcessTranslation() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStreamSource<Long> src = env.fromSequence(0, 0);

        KeyedProcessFunction<Long, Long, Integer> keyedProcessFunction =
                new KeyedProcessFunction<Long, Long, Integer>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void processElement(Long value, Context ctx, Collector<Integer> out)
                            throws Exception {
                        // Do nothing
                    }

                    @Override
                    public void onTimer(long timestamp, OnTimerContext ctx, Collector<Integer> out)
                            throws Exception {
                        // Do nothing
                    }
                };

        DataStream<Integer> processed =
                src.keyBy(new IdentityKeySelector<Long>()).process(keyedProcessFunction);

        processed.sinkTo(new DiscardingSink<Integer>());

        assertThat(getFunctionForDataStream(processed)).isEqualTo(keyedProcessFunction);
        assertThat(getOperatorForDataStream(processed)).isInstanceOf(KeyedProcessOperator.class);
    }

    /**
     * Verify that a {@link KeyedStream#process(KeyedProcessFunction)} call is correctly translated
     * to an async operator.
     */
    @Test
    void testAsyncKeyedStreamKeyedProcessTranslation() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStreamSource<Long> src = env.fromSequence(0, 0);

        KeyedProcessFunction<Long, Long, Integer> keyedProcessFunction =
                new KeyedProcessFunction<Long, Long, Integer>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void processElement(Long value, Context ctx, Collector<Integer> out)
                            throws Exception {
                        // Do nothing
                    }

                    @Override
                    public void onTimer(long timestamp, OnTimerContext ctx, Collector<Integer> out)
                            throws Exception {
                        // Do nothing
                    }
                };

        DataStream<Integer> processed =
                src.keyBy(new IdentityKeySelector<Long>())
                        .enableAsyncState()
                        .process(keyedProcessFunction);

        processed.sinkTo(new DiscardingSink<Integer>());

        assertThat(
                        ((AbstractAsyncStateUdfStreamOperator<?, ?>)
                                        getOperatorForDataStream(processed))
                                .getUserFunction())
                .isEqualTo(keyedProcessFunction);
        assertThat(getOperatorForDataStream(processed))
                .isInstanceOf(AsyncKeyedProcessOperator.class);
    }

    /**
     * Verify that a {@link DataStream#process(ProcessFunction)} call is correctly translated to an
     * operator.
     */
    @Test
    void testProcessTranslation() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStreamSource<Long> src = env.fromSequence(0, 0);

        ProcessFunction<Long, Integer> processFunction =
                new ProcessFunction<Long, Integer>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void processElement(Long value, Context ctx, Collector<Integer> out)
                            throws Exception {
                        // Do nothing
                    }

                    @Override
                    public void onTimer(long timestamp, OnTimerContext ctx, Collector<Integer> out)
                            throws Exception {
                        // Do nothing
                    }
                };

        DataStream<Integer> processed = src.process(processFunction);

        processed.sinkTo(new DiscardingSink<Integer>());

        assertThat(getFunctionForDataStream(processed)).isEqualTo(processFunction);
        assertThat(getOperatorForDataStream(processed)).isInstanceOf(ProcessOperator.class);
    }

    /**
     * Tests that with a {@link KeyedStream} we have to provide a {@link
     * KeyedBroadcastProcessFunction}.
     */
    @Test
    void testFailedTranslationOnKeyed() {

        final MapStateDescriptor<Long, String> descriptor =
                new MapStateDescriptor<>(
                        "broadcast", BasicTypeInfo.LONG_TYPE_INFO, BasicTypeInfo.STRING_TYPE_INFO);

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        final DataStream<Long> srcOne =
                env.fromSequence(0L, 5L)
                        .assignTimestampsAndWatermarks(
                                new CustomWmEmitter<Long>() {

                                    @Override
                                    public long extractTimestamp(
                                            Long element, long previousElementTimestamp) {
                                        return element;
                                    }
                                })
                        .keyBy((KeySelector<Long, Long>) value -> value);

        final DataStream<String> srcTwo =
                env.fromData("Test:0", "Test:1", "Test:2", "Test:3", "Test:4", "Test:5")
                        .assignTimestampsAndWatermarks(
                                new CustomWmEmitter<String>() {
                                    @Override
                                    public long extractTimestamp(
                                            String element, long previousElementTimestamp) {
                                        return Long.parseLong(element.split(":")[1]);
                                    }
                                });

        BroadcastStream<String> broadcast = srcTwo.broadcast(descriptor);
        BroadcastConnectedStream<Long, String> bcStream = srcOne.connect(broadcast);

        assertThatThrownBy(
                        () ->
                                bcStream.process(
                                        new BroadcastProcessFunction<Long, String, String>() {
                                            @Override
                                            public void processBroadcastElement(
                                                    String value,
                                                    Context ctx,
                                                    Collector<String> out) {
                                                // do nothing
                                            }

                                            @Override
                                            public void processElement(
                                                    Long value,
                                                    ReadOnlyContext ctx,
                                                    Collector<String> out) {
                                                // do nothing
                                            }
                                        }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Tests that with a non-keyed stream we have to provide a {@link BroadcastProcessFunction}. */
    @Test
    void testFailedTranslationOnNonKeyed() {

        final MapStateDescriptor<Long, String> descriptor =
                new MapStateDescriptor<>(
                        "broadcast", BasicTypeInfo.LONG_TYPE_INFO, BasicTypeInfo.STRING_TYPE_INFO);

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        final DataStream<Long> srcOne =
                env.fromSequence(0L, 5L)
                        .assignTimestampsAndWatermarks(
                                new CustomWmEmitter<Long>() {

                                    @Override
                                    public long extractTimestamp(
                                            Long element, long previousElementTimestamp) {
                                        return element;
                                    }
                                });

        final DataStream<String> srcTwo =
                env.fromData("Test:0", "Test:1", "Test:2", "Test:3", "Test:4", "Test:5")
                        .assignTimestampsAndWatermarks(
                                new CustomWmEmitter<String>() {
                                    @Override
                                    public long extractTimestamp(
                                            String element, long previousElementTimestamp) {
                                        return Long.parseLong(element.split(":")[1]);
                                    }
                                });

        BroadcastStream<String> broadcast = srcTwo.broadcast(descriptor);
        BroadcastConnectedStream<Long, String> bcStream = srcOne.connect(broadcast);

        assertThatThrownBy(
                        () ->
                                bcStream.process(
                                        new KeyedBroadcastProcessFunction<
                                                String, Long, String, String>() {
                                            @Override
                                            public void processBroadcastElement(
                                                    String value,
                                                    Context ctx,
                                                    Collector<String> out) {
                                                // do nothing
                                            }

                                            @Override
                                            public void processElement(
                                                    Long value,
                                                    ReadOnlyContext ctx,
                                                    Collector<String> out) {
                                                // do nothing
                                            }
                                        }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Tests that verifies window operator has different name and description. */
    @Test
    void testWindowOperatorDescription() {
        // global window
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStream<Long> dataStream1 =
                env.fromSequence(0, 0)
                        .windowAll(GlobalWindows.create())
                        .trigger(PurgingTrigger.of(CountTrigger.of(10)))
                        .reduce(
                                new ReduceFunction<Long>() {
                                    private static final long serialVersionUID = 1L;

                                    @Override
                                    public Long reduce(Long value1, Long value2) throws Exception {
                                        return null;
                                    }
                                });
        // name is simplified
        assertThat(dataStream1.getTransformation().getName()).isEqualTo("GlobalWindows");
        // description contains detail of function:
        // TriggerWindow(GlobalWindows(), ReducingStateDescriptor{name=window-contents,
        // defaultValue=null,
        // serializer=org.apache.flink.api.common.typeutils.base.LongSerializer@6af9fcb2},
        // PurgingTrigger(CountTrigger(10)), AllWindowedStream.reduce(AllWindowedStream.java:229))
        assertThat(dataStream1.getTransformation().getDescription()).contains("PurgingTrigger");

        // keyed window
        DataStream<Long> dataStream2 =
                env.fromSequence(0, 0)
                        .keyBy(value -> value)
                        .window(TumblingEventTimeWindows.of(Duration.ofMillis(1000)))
                        .trigger(PurgingTrigger.of(CountTrigger.of(10)))
                        .reduce(
                                new ReduceFunction<Long>() {
                                    private static final long serialVersionUID = 1L;

                                    @Override
                                    public Long reduce(Long value1, Long value2) throws Exception {
                                        return null;
                                    }
                                });
        // name is simplified
        assertThat(dataStream2.getTransformation().getName()).isEqualTo("TumblingEventTimeWindows");
        // description contains detail of function:
        // Window(TumblingEventTimeWindows(1000), PurgingTrigger, ReduceFunction$36,
        // PassThroughWindowFunction)
        assertThat(dataStream2.getTransformation().getDescription()).contains("PurgingTrigger");
    }

    /**
     * Tests {@link SingleOutputStreamOperator#setDescription(String)} functionality.
     *
     * @throws Exception
     */
    @Test
    void testUserDefinedDescription() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<Long> dataStream1 =
                env.fromSequence(0, 0)
                        .name("testSource1")
                        .setDescription("this is test source 1")
                        .map(
                                new MapFunction<Long, Long>() {
                                    @Override
                                    public Long map(Long value) throws Exception {
                                        return null;
                                    }
                                })
                        .name("testMap")
                        .setDescription("this is test map 1");

        DataStream<Long> dataStream2 =
                env.fromSequence(0, 0)
                        .name("testSource2")
                        .setDescription("this is test source 2")
                        .map(
                                new MapFunction<Long, Long>() {
                                    @Override
                                    public Long map(Long value) throws Exception {
                                        return null;
                                    }
                                })
                        .name("testMap")
                        .setDescription("this is test map 2");

        dataStream1
                .connect(dataStream2)
                .flatMap(
                        new CoFlatMapFunction<Long, Long, Long>() {

                            @Override
                            public void flatMap1(Long value, Collector<Long> out)
                                    throws Exception {}

                            @Override
                            public void flatMap2(Long value, Collector<Long> out)
                                    throws Exception {}
                        })
                .name("testCoFlatMap")
                .setDescription("this is test co flat map")
                .windowAll(GlobalWindows.create())
                .trigger(PurgingTrigger.of(CountTrigger.of(10)))
                .reduce(
                        new ReduceFunction<Long>() {
                            private static final long serialVersionUID = 1L;

                            @Override
                            public Long reduce(Long value1, Long value2) throws Exception {
                                return null;
                            }
                        })
                .name("testWindowReduce")
                .setDescription("this is test window reduce")
                .print();

        // test functionality through the operator names in the execution plan
        String plan = env.getExecutionPlan();

        assertThat(plan)
                .contains(
                        "this is test source 1",
                        "this is test map 1",
                        "this is test map 2",
                        "this is test co flat map",
                        "this is test window reduce");
    }

    private abstract static class CustomWmEmitter<T>
            implements WatermarkStrategyWithPunctuatedWatermarks<T> {

        @Nullable
        @Override
        public Watermark checkAndGetNextWatermark(T lastElement, long extractedTimestamp) {
            return new Watermark(extractedTimestamp);
        }
    }

    @Test
    void operatorTest() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStreamSource<Long> src = env.fromSequence(0, 0);

        MapFunction<Long, Integer> mapFunction =
                new MapFunction<Long, Integer>() {
                    @Override
                    public Integer map(Long value) throws Exception {
                        return null;
                    }
                };
        DataStream<Integer> map = src.map(mapFunction);
        map.sinkTo(new DiscardingSink<Integer>());
        assertThat(getFunctionForDataStream(map)).isEqualTo(mapFunction);

        FlatMapFunction<Long, Integer> flatMapFunction =
                new FlatMapFunction<Long, Integer>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void flatMap(Long value, Collector<Integer> out) throws Exception {}
                };
        DataStream<Integer> flatMap = src.flatMap(flatMapFunction);
        flatMap.sinkTo(new DiscardingSink<Integer>());
        assertThat(getFunctionForDataStream(flatMap)).isEqualTo(flatMapFunction);

        FilterFunction<Integer> filterFunction =
                new FilterFunction<Integer>() {
                    @Override
                    public boolean filter(Integer value) throws Exception {
                        return false;
                    }
                };

        DataStream<Integer> unionFilter = map.union(flatMap).filter(filterFunction);

        unionFilter.sinkTo(new DiscardingSink<Integer>());

        assertThat(getFunctionForDataStream(unionFilter)).isEqualTo(filterFunction);

        getStreamGraph(env).getStreamEdgesOrThrow(map.getId(), unionFilter.getId());
        getStreamGraph(env).getStreamEdgesOrThrow(flatMap.getId(), unionFilter.getId());

        ConnectedStreams<Integer, Integer> connect = map.connect(flatMap);
        CoMapFunction<Integer, Integer, String> coMapper =
                new CoMapFunction<Integer, Integer, String>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public String map1(Integer value) {
                        return null;
                    }

                    @Override
                    public String map2(Integer value) {
                        return null;
                    }
                };
        DataStream<String> coMap = connect.map(coMapper);
        coMap.sinkTo(new DiscardingSink<String>());
        assertThat(getFunctionForDataStream(coMap)).isEqualTo(coMapper);

        getStreamGraph(env).getStreamEdgesOrThrow(map.getId(), coMap.getId());
        getStreamGraph(env).getStreamEdgesOrThrow(flatMap.getId(), coMap.getId());
    }

    @Test
    void testKeyedConnectedStreamsType() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStreamSource<Integer> stream1 = env.fromData(1, 2);
        DataStreamSource<Integer> stream2 = env.fromData(1, 2);

        ConnectedStreams<Integer, Integer> connectedStreams =
                stream1.connect(stream2).keyBy(v -> v, v -> v);

        KeyedStream<?, ?> firstKeyedInput = (KeyedStream<?, ?>) connectedStreams.getFirstInput();
        KeyedStream<?, ?> secondKeyedInput = (KeyedStream<?, ?>) connectedStreams.getSecondInput();
        assertThat(firstKeyedInput.getKeyType()).isEqualTo(Types.INT);
        assertThat(secondKeyedInput.getKeyType()).isEqualTo(Types.INT);
    }

    @Test
    void sinkKeyTest() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStreamSink<Long> sink = env.fromSequence(1, 100).print();
        assertThat(
                        getStreamGraph(env)
                                .getStreamNode(sink.getTransformation().getId())
                                .getStatePartitioners()
                                .length)
                .isZero();
        assertThat(
                        getStreamGraph(env)
                                .getStreamNode(sink.getTransformation().getId())
                                .getInEdges()
                                .get(0)
                                .getPartitioner())
                .isInstanceOf(ForwardPartitioner.class);

        KeySelector<Long, Long> key1 =
                new KeySelector<Long, Long>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Long getKey(Long value) throws Exception {
                        return (long) 0;
                    }
                };

        DataStreamSink<Long> sink2 = env.fromSequence(1, 100).keyBy(key1).print();

        assertThat(
                        getStreamGraph(env)
                                .getStreamNode(sink2.getTransformation().getId())
                                .getStatePartitioners()
                                .length)
                .isOne();
        assertThat(
                        getStreamGraph(env)
                                .getStreamNode(sink2.getTransformation().getId())
                                .getStateKeySerializer())
                .isNotNull();
        assertThat(
                        getStreamGraph(env)
                                .getStreamNode(sink2.getTransformation().getId())
                                .getStateKeySerializer())
                .isNotNull();
        assertThat(
                        getStreamGraph(env)
                                .getStreamNode(sink2.getTransformation().getId())
                                .getStatePartitioners()[0])
                .isEqualTo(key1);
        assertThat(
                        getStreamGraph(env)
                                .getStreamNode(sink2.getTransformation().getId())
                                .getInEdges()
                                .get(0)
                                .getPartitioner())
                .isInstanceOf(KeyGroupStreamPartitioner.class);

        KeySelector<Long, Long> key2 =
                new KeySelector<Long, Long>() {

                    private static final long serialVersionUID = 1L;

                    @Override
                    public Long getKey(Long value) throws Exception {
                        return (long) 0;
                    }
                };

        DataStreamSink<Long> sink3 = env.fromSequence(1, 100).keyBy(key2).print();

        assertThat(
                        getStreamGraph(env)
                                .getStreamNode(sink3.getTransformation().getId())
                                .getStatePartitioners()
                                .length)
                .isOne();
        assertThat(
                        getStreamGraph(env)
                                .getStreamNode(sink3.getTransformation().getId())
                                .getStatePartitioners()[0])
                .isEqualTo(key2);
        assertThat(
                        getStreamGraph(env)
                                .getStreamNode(sink3.getTransformation().getId())
                                .getInEdges()
                                .get(0)
                                .getPartitioner())
                .isInstanceOf(KeyGroupStreamPartitioner.class);
    }

    @Test
    void testChannelSelectors() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStreamSource<Long> src = env.fromSequence(0, 0);

        DataStream<Long> broadcast = src.broadcast();
        DataStreamSink<Long> broadcastSink = broadcast.print();
        StreamPartitioner<?> broadcastPartitioner =
                getStreamGraph(env)
                        .getStreamEdges(src.getId(), broadcastSink.getTransformation().getId())
                        .get(0)
                        .getPartitioner();
        assertThat(broadcastPartitioner).isInstanceOf(BroadcastPartitioner.class);

        DataStream<Long> shuffle = src.shuffle();
        DataStreamSink<Long> shuffleSink = shuffle.print();
        StreamPartitioner<?> shufflePartitioner =
                getStreamGraph(env)
                        .getStreamEdges(src.getId(), shuffleSink.getTransformation().getId())
                        .get(0)
                        .getPartitioner();
        assertThat(shufflePartitioner).isInstanceOf(ShufflePartitioner.class);

        DataStream<Long> forward = src.forward();
        DataStreamSink<Long> forwardSink = forward.print();
        StreamPartitioner<?> forwardPartitioner =
                getStreamGraph(env)
                        .getStreamEdges(src.getId(), forwardSink.getTransformation().getId())
                        .get(0)
                        .getPartitioner();
        assertThat(forwardPartitioner).isInstanceOf(ForwardPartitioner.class);

        DataStream<Long> rebalance = src.rebalance();
        DataStreamSink<Long> rebalanceSink = rebalance.print();
        StreamPartitioner<?> rebalancePartitioner =
                getStreamGraph(env)
                        .getStreamEdges(src.getId(), rebalanceSink.getTransformation().getId())
                        .get(0)
                        .getPartitioner();
        assertThat(rebalancePartitioner).isInstanceOf(RebalancePartitioner.class);

        DataStream<Long> global = src.global();
        DataStreamSink<Long> globalSink = global.print();
        StreamPartitioner<?> globalPartitioner =
                getStreamGraph(env)
                        .getStreamEdges(src.getId(), globalSink.getTransformation().getId())
                        .get(0)
                        .getPartitioner();
        assertThat(globalPartitioner).isInstanceOf(GlobalPartitioner.class);
    }

    /////////////////////////////////////////////////////////////
    // KeyBy testing
    /////////////////////////////////////////////////////////////

    @Test
    void testPrimitiveArrayKeyRejection() {

        KeySelector<Tuple2<Integer[], String>, int[]> keySelector =
                new KeySelector<Tuple2<Integer[], String>, int[]>() {

                    @Override
                    public int[] getKey(Tuple2<Integer[], String> value) throws Exception {
                        int[] ks = new int[value.f0.length];
                        for (int i = 0; i < ks.length; i++) {
                            ks[i] = value.f0[i];
                        }
                        return ks;
                    }
                };

        assertArrayKeyRejection(keySelector, PrimitiveArrayTypeInfo.INT_PRIMITIVE_ARRAY_TYPE_INFO);
    }

    @Test
    void testBasicArrayKeyRejection() {

        KeySelector<Tuple2<Integer[], String>, Integer[]> keySelector =
                new KeySelector<Tuple2<Integer[], String>, Integer[]>() {

                    @Override
                    public Integer[] getKey(Tuple2<Integer[], String> value) throws Exception {
                        return value.f0;
                    }
                };

        assertArrayKeyRejection(keySelector, BasicArrayTypeInfo.INT_ARRAY_TYPE_INFO);
    }

    @Test
    void testObjectArrayKeyRejection() {

        KeySelector<Tuple2<Integer[], String>, Object[]> keySelector =
                new KeySelector<Tuple2<Integer[], String>, Object[]>() {

                    @Override
                    public Object[] getKey(Tuple2<Integer[], String> value) throws Exception {
                        Object[] ks = new Object[value.f0.length];
                        for (int i = 0; i < ks.length; i++) {
                            ks[i] = new Object();
                        }
                        return ks;
                    }
                };

        ObjectArrayTypeInfo<Object[], Object> keyTypeInfo =
                ObjectArrayTypeInfo.getInfoFor(Object[].class, new GenericTypeInfo<>(Object.class));

        assertArrayKeyRejection(keySelector, keyTypeInfo);
    }

    private <K> void assertArrayKeyRejection(
            KeySelector<Tuple2<Integer[], String>, K> keySelector,
            TypeInformation<K> expectedKeyType) {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<Tuple2<Integer[], String>> input =
                env.fromData(new Tuple2<>(new Integer[] {1, 2}, "barfoo"));

        assertThat(TypeExtractor.getKeySelectorTypes(keySelector, input.getType()))
                .isEqualTo(expectedKeyType);

        // adjust the rule
        assertThatThrownBy(() -> input.keyBy(keySelector))
                .isInstanceOf(InvalidProgramException.class)
                .hasMessageStartingWith("Type " + expectedKeyType + " cannot be used as key.");
    }

    @Test
    void testEnumKeyRejection() {
        KeySelector<Tuple2<TestEnum, String>, TestEnum> keySelector = value -> value.f0;

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<Tuple2<TestEnum, String>> input =
                env.fromData(Tuple2.of(TestEnum.FOO, "Foo"), Tuple2.of(TestEnum.BAR, "Bar"));

        assertThatThrownBy(() -> input.keyBy(keySelector))
                .isInstanceOf(InvalidProgramException.class)
                .hasMessageStartingWith(
                        "Type " + EnumTypeInfo.of(TestEnum.class) + " cannot be used as key.");
    }

    ////////////////			Composite Key Tests : POJOs			////////////////

    @Test
    void testPOJOWithNestedArrayNoHashCodeKeyRejection() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<POJOWithHashCode> input = env.fromData(new POJOWithHashCode(new int[] {1, 2}));

        TypeInformation<?> expectedTypeInfo = PrimitiveArrayTypeInfo.INT_PRIMITIVE_ARRAY_TYPE_INFO;

        // adjust the rule
        assertThatThrownBy(() -> input.keyBy(POJOWithoutHashCode::getId))
                .isInstanceOf(InvalidProgramException.class)
                .hasMessageStartingWith("Type " + expectedTypeInfo + " cannot be used as key.");
    }

    @Test
    void testPOJOWithNestedArrayAndHashCodeWorkAround() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<POJOWithHashCode> input = env.fromData(new POJOWithHashCode(new int[] {1, 2}));

        input.keyBy(
                        new KeySelector<POJOWithHashCode, POJOWithHashCode>() {
                            @Override
                            public POJOWithHashCode getKey(POJOWithHashCode value)
                                    throws Exception {
                                return value;
                            }
                        })
                .addSink(
                        new SinkFunction<POJOWithHashCode>() {
                            @Override
                            public void invoke(POJOWithHashCode value) {
                                assertThat(value.getId()).containsExactly(1, 2);
                            }
                        });
    }

    @Test
    void testPOJOnoHashCodeKeyRejection() {

        KeySelector<POJOWithoutHashCode, POJOWithoutHashCode> keySelector =
                new KeySelector<POJOWithoutHashCode, POJOWithoutHashCode>() {
                    @Override
                    public POJOWithoutHashCode getKey(POJOWithoutHashCode value) throws Exception {
                        return value;
                    }
                };

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<POJOWithoutHashCode> input =
                env.fromData(new POJOWithoutHashCode(new int[] {1, 2}));

        // adjust the rule
        assertThatThrownBy(() -> input.keyBy(keySelector))
                .isInstanceOf(InvalidProgramException.class);
    }

    ////////////////			Composite Key Tests : Tuples			////////////////

    @Test
    void testTupleNestedArrayKeyRejection() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<Tuple2<Integer[], String>> input =
                env.fromData(new Tuple2<>(new Integer[] {1, 2}, "test-test"));

        TypeInformation<?> expectedTypeInfo =
                new TupleTypeInfo<Tuple2<Integer[], String>>(
                        BasicArrayTypeInfo.INT_ARRAY_TYPE_INFO, BasicTypeInfo.STRING_TYPE_INFO);

        // adjust the rule
        assertThatThrownBy(
                        () ->
                                input.keyBy(
                                        new KeySelector<
                                                Tuple2<Integer[], String>,
                                                Tuple2<Integer[], String>>() {
                                            @Override
                                            public Tuple2<Integer[], String> getKey(
                                                    Tuple2<Integer[], String> value)
                                                    throws Exception {
                                                return value;
                                            }
                                        }))
                .isInstanceOf(InvalidProgramException.class)
                .hasMessageStartingWith("Type " + expectedTypeInfo + " cannot be used as key.");
    }

    @Test
    void testPrimitiveKeyAcceptance() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        env.setMaxParallelism(1);

        DataStream<Integer> input = env.fromData(new Integer(10000));

        KeyedStream<Integer, Object> keyedStream =
                input.keyBy(
                        new KeySelector<Integer, Object>() {
                            @Override
                            public Object getKey(Integer value) throws Exception {
                                return value;
                            }
                        });

        keyedStream.addSink(
                new SinkFunction<Integer>() {
                    @Override
                    public void invoke(Integer value) throws Exception {
                        assertThat(value).isEqualTo(10000);
                    }
                });
    }

    /** POJO without hashCode. */
    public static class POJOWithoutHashCode {

        private int[] id;

        public POJOWithoutHashCode() {}

        public POJOWithoutHashCode(int[] id) {
            this.id = id;
        }

        public int[] getId() {
            return id;
        }

        public void setId(int[] id) {
            this.id = id;
        }
    }

    /** POJO with hashCode. */
    public static class POJOWithHashCode extends POJOWithoutHashCode {

        public POJOWithHashCode() {}

        public POJOWithHashCode(int[] id) {
            super(id);
        }

        @Override
        public int hashCode() {
            int hash = 31;
            for (int i : getId()) {
                hash = 37 * hash + i;
            }
            return hash;
        }
    }

    /////////////////////////////////////////////////////////////
    // Utilities
    /////////////////////////////////////////////////////////////

    private static StreamOperator<?> getOperatorForDataStream(DataStream<?> dataStream) {
        StreamExecutionEnvironment env = dataStream.getExecutionEnvironment();
        StreamGraph streamGraph = getStreamGraph(env);
        return streamGraph.getStreamNode(dataStream.getId()).getOperator();
    }

    private static Function getFunctionForDataStream(DataStream<?> dataStream) {
        AbstractUdfStreamOperator<?, ?> operator =
                (AbstractUdfStreamOperator<?, ?>) getOperatorForDataStream(dataStream);
        return operator.getUserFunction();
    }

    /** Returns the StreamGraph without clearing the transformations. */
    private static StreamGraph getStreamGraph(StreamExecutionEnvironment sEnv) {
        return sEnv.getStreamGraph(false);
    }

    private static Integer createDownStreamId(DataStream<?> dataStream) {
        return dataStream.print().getTransformation().getId();
    }

    private static boolean isKeyed(DataStream<?> dataStream) {
        return dataStream instanceof KeyedStream;
    }

    @SuppressWarnings("rawtypes,unchecked")
    private static Integer createDownStreamId(ConnectedStreams dataStream) {
        SingleOutputStreamOperator<?> coMap =
                dataStream.map(
                        new CoMapFunction<Tuple2<Long, Long>, Tuple2<Long, Long>, Object>() {
                            private static final long serialVersionUID = 1L;

                            @Override
                            public Object map1(Tuple2<Long, Long> value) {
                                return null;
                            }

                            @Override
                            public Object map2(Tuple2<Long, Long> value) {
                                return null;
                            }
                        });
        coMap.sinkTo(new DiscardingSink());
        return coMap.getId();
    }

    private static boolean isKeyed(ConnectedStreams<?, ?> dataStream) {
        return (dataStream.getFirstInput() instanceof KeyedStream
                && dataStream.getSecondInput() instanceof KeyedStream);
    }

    private static boolean isPartitioned(List<StreamEdge> edges) {
        boolean result = true;
        for (StreamEdge edge : edges) {
            if (!(edge.getPartitioner() instanceof KeyGroupStreamPartitioner)) {
                result = false;
            }
        }
        return result;
    }

    private static boolean isCustomPartitioned(List<StreamEdge> edges) {
        boolean result = true;
        for (StreamEdge edge : edges) {
            if (!(edge.getPartitioner() instanceof CustomPartitionerWrapper)) {
                result = false;
            }
        }
        return result;
    }

    private static class FirstSelector implements KeySelector<Tuple2<Long, Long>, Long> {
        private static final long serialVersionUID = 1L;

        @Override
        public Long getKey(Tuple2<Long, Long> value) throws Exception {
            return value.f0;
        }
    }

    private static class IdentityKeySelector<T> implements KeySelector<T, T> {
        private static final long serialVersionUID = 1L;

        @Override
        public T getKey(T value) throws Exception {
            return value;
        }
    }

    private static class CustomPOJO {
        private String s;
        private int i;

        public CustomPOJO() {}

        public void setS(String s) {
            this.s = s;
        }

        public void setI(int i) {
            this.i = i;
        }

        public String getS() {
            return s;
        }

        public int getI() {
            return i;
        }
    }

    private enum TestEnum {
        FOO,
        BAR
    }
}
