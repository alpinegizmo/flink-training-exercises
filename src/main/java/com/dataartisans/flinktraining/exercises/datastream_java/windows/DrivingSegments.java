/*
 * Copyright 2017 data Artisans GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dataartisans.flinktraining.exercises.datastream_java.windows;

import com.dataartisans.flinktraining.exercises.datastream_java.datatypes.ConnectedCarEvent;
import com.dataartisans.flinktraining.exercises.datastream_java.datatypes.StoppedSegment;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.base.BooleanSerializer;
import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.AssignerWithPunctuatedWatermarks;
import org.apache.flink.streaming.api.functions.windowing.WindowFunction;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.api.windowing.assigners.GlobalWindows;
import org.apache.flink.streaming.api.windowing.evictors.Evictor;
import org.apache.flink.streaming.api.windowing.triggers.Trigger;
import org.apache.flink.streaming.api.windowing.triggers.TriggerResult;
import org.apache.flink.streaming.api.windowing.windows.GlobalWindow;
import org.apache.flink.streaming.runtime.operators.windowing.TimestampedValue;
import org.apache.flink.util.Collector;

import java.util.Iterator;
import java.util.TreeSet;

/**
 * Java reference implementation for the "Driving Segments" exercise of the Flink training
 * (http://dataartisans.github.io/flink-training).
 *
 * The task of the exercise is to divide the input stream of ConnectedCarEvents into segments,
 * where the car is being continously driven without stopping.
 *
 * Parameters:
 * -input path-to-input-file
 *
 */
public class DrivingSegments {

	public static void main(String[] args) throws Exception {

		// read parameters
		ParameterTool params = ParameterTool.fromArgs(args);
		String input = params.getRequired("input");

		// set up streaming execution environment
		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.setParallelism(1);
		env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

		// auto watermarking doesn't work in this case, because the source runs to completion quickly
		// and the watermarking never gets a chance to run (unlike a long-running streaming job)
// 		env.getConfig().setAutoWatermarkInterval(1);

		// connect to the data file
		DataStream<String> carData = env.readTextFile(input);

		// find segments
		DataStream<ConnectedCarEvent> events = carData
				.map(new MapFunction<String, ConnectedCarEvent>() {
					@Override
					public ConnectedCarEvent map(String line) throws Exception {
						return ConnectedCarEvent.fromString(line);
					}
				})
				.assignTimestampsAndWatermarks(new ConnectedCarAssigner());

		events.keyBy("car_id")
		        .window(GlobalWindows.create())
				.trigger(new SegmentingOutOfOrderTrigger())
				.evictor(new SegmentingEvictor())
				.apply(new CreateStoppedSegment())
				.print();

		env.execute("Driving Segments");
	}

	public static class SegmentingInOrderTrigger extends Trigger<ConnectedCarEvent, GlobalWindow> {
		private final ValueStateDescriptor<Boolean> stoppedState =
				new ValueStateDescriptor<Boolean>("stopped", BooleanSerializer.INSTANCE);

		@Override
		public TriggerResult onElement(ConnectedCarEvent event, long timestamp, GlobalWindow window, TriggerContext ctx) throws Exception {
			ValueState<Boolean> stopped = ctx.getPartitionedState(stoppedState);

			if (stopped.value() == null) {
				if (event.speed == 0.0) stopped.update(true);
				else stopped.update(false);
			}
			else {
				if (stopped.value() == true && event.speed > 0.0) {
					stopped.update(false);
					return TriggerResult.FIRE;
				}
				if (stopped.value() == false && event.speed == 0.0) {
					stopped.update(true);
					return TriggerResult.FIRE;
				}
			}
			return TriggerResult.CONTINUE;
		}

		@Override
		public TriggerResult onEventTime(long time, GlobalWindow window, TriggerContext ctx) {
			return TriggerResult.CONTINUE;
		}

		@Override
		public TriggerResult onProcessingTime(long time, GlobalWindow window, TriggerContext ctx) {
			return TriggerResult.CONTINUE;
		}

		@Override
		public void clear(GlobalWindow window, TriggerContext ctx) {
			ctx.getPartitionedState(stoppedState).clear();
		}

		@Override
		public boolean canMerge() {
			return false;
		}
	}

	public static class SegmentingOutOfOrderTrigger extends Trigger<ConnectedCarEvent, GlobalWindow> {
		private final ValueStateDescriptor<TreeSet<Long>> stoppingTimesDesc =
				new ValueStateDescriptor<TreeSet<Long>>("stopping-times-desc",
						TypeInformation.of(new TypeHint<TreeSet<Long>>() {}));

		@Override
		public TriggerResult onElement(ConnectedCarEvent event,
									   long timestamp,
									   GlobalWindow window,
									   TriggerContext ctx) throws Exception {
			ValueState<TreeSet<Long>> stoppingTimes = ctx.getPartitionedState(stoppingTimesDesc);
			TreeSet<Long> setOfTimes = stoppingTimes.value();

			if (event.speed == 0.0) {
				if (setOfTimes == null) {
					setOfTimes = new TreeSet<Long>();
				}
				setOfTimes.add(event.timestamp);
				stoppingTimes.update(setOfTimes);
			}

			if (setOfTimes != null && !setOfTimes.isEmpty()) {
				java.util.Iterator<Long> iter = setOfTimes.iterator();
				long nextStop = iter.next();
				if (ctx.getCurrentWatermark() >= nextStop) {
					iter.remove();
					stoppingTimes.update(setOfTimes);
					return TriggerResult.FIRE;
				}
			}

			return TriggerResult.CONTINUE;
		}

		@Override
		public TriggerResult onEventTime(long time, GlobalWindow window, TriggerContext ctx) {
			return TriggerResult.CONTINUE;
		}

		@Override
		public TriggerResult onProcessingTime(long time, GlobalWindow window, TriggerContext ctx) {
			return TriggerResult.CONTINUE;
		}

		@Override
		public void clear(GlobalWindow window, TriggerContext ctx) {
			ctx.getPartitionedState(stoppingTimesDesc).clear();
		}
	}

	public static class SegmentingEvictor implements Evictor<ConnectedCarEvent, GlobalWindow> {

		@Override
		public void evictBefore(Iterable<TimestampedValue<ConnectedCarEvent>> events,
								int size, GlobalWindow window, EvictorContext ctx) {
		}

		@Override
		public void evictAfter(Iterable<TimestampedValue<ConnectedCarEvent>> elements,
							   int size, GlobalWindow window, EvictorContext ctx) {
			long firstStop = ConnectedCarEvent.earliestStopElement(elements);

			for (Iterator<TimestampedValue<ConnectedCarEvent>> iterator = elements.iterator(); iterator.hasNext();) {
				TimestampedValue<ConnectedCarEvent> element = iterator.next();
				if (element.getTimestamp() <= firstStop) {
					iterator.remove();
				}
			}
		}
	}

	/**
	 * Assigns timestamps to the events.
	 * Watermarks are a fixed time interval behind the max timestamp and are periodically emitted.
	 */
//	public static class ConnectedCarTSExtractor extends BoundedOutOfOrdernessTimestampExtractor<ConnectedCarEvent> {
//		public ConnectedCarTSExtractor() {
//			super(Time.seconds(10));
//		}
//
//		@Override
//		public long extractTimestamp(ConnectedCarEvent event) {
//			return event.timestamp;
//		}
//	}

	public static class ConnectedCarAssigner implements AssignerWithPunctuatedWatermarks<ConnectedCarEvent> {
		@Override
		public long extractTimestamp(ConnectedCarEvent event, long previousElementTimestamp) {
			return event.timestamp;
		}

		@Override
		public Watermark checkAndGetNextWatermark(ConnectedCarEvent event, long extractedTimestamp) {
			return new Watermark(extractedTimestamp - 30000);
		}
	}

	public static class CreateStoppedSegment implements WindowFunction<ConnectedCarEvent, StoppedSegment, Tuple, GlobalWindow> {
		@Override
		public void apply(Tuple key, GlobalWindow window, Iterable<ConnectedCarEvent> events, Collector<StoppedSegment> out) {
			StoppedSegment seg = new StoppedSegment(events);
			if (seg.length > 0) {
				out.collect(seg);
			}
		}

	}
}
