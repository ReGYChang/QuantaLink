package io.github.regychang.flinkify.quickstart.connector.mongo;

import io.github.regychang.flinkify.common.exception.FlinkException;
import io.github.regychang.flinkify.common.type.TypeInformation;
import io.github.regychang.flinkify.flink.core.connector.kafka.serialization.KafkaDeserializationAdapter;
import io.github.regychang.flinkify.flink.core.connector.mongo.serialization.MongoSerializationAdapter;
import io.github.regychang.flinkify.flink.core.streaming.FlinkDataStream;
import io.github.regychang.flinkify.flink.core.streaming.FlinkStreaming;
import io.github.regychang.flinkify.flink.core.streaming.FlinkStreamingContext;
import io.github.regychang.flinkify.flink.core.streaming.FlinkStreamingInitializer;
import io.github.regychang.flinkify.quickstart.connector.mongo.entity.DcsEvent;
import io.github.regychang.flinkify.quickstart.connector.mongo.entity.Record;
import io.github.regychang.flinkify.quickstart.connector.mongo.entity.SensorMap;
import io.github.regychang.flinkify.quickstart.connector.mongo.process.TagProductionLineToDcs;
import io.github.regychang.flinkify.quickstart.connector.mongo.serialization.PayloadDeserializationSchema;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateManyModel;
import com.mongodb.client.model.Updates;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;

import java.util.Map;

public class MongoSink extends FlinkStreaming {

    private final MapStateDescriptor<String, Map<String, SensorMap.ProductionLine>>
            SENSOR_MAP_DESC =
                    new MapStateDescriptor<>(
                            "sensor_map-state",
                            Types.STRING,
                            Types.MAP(Types.STRING, Types.POJO(SensorMap.ProductionLine.class)));

    public static void main(String[] args) throws Exception {
        FlinkStreamingInitializer initializer =
                new FlinkStreamingInitializer.Builder()
                        .withExecutionEnvironmentSetup(
                                env -> {
                                    env.enableCheckpointing(3000);
                                    env.setParallelism(1);
                                })
                        .withSourceConnectorSetup(
                                (sourceConnector, config) ->
                                        sourceConnector.withDeserializationSchemaAdapter(
                                                KafkaDeserializationAdapter.valueOnly(
                                                        new PayloadDeserializationSchema<>(
                                                                TypeInformation.get(
                                                                        DcsEvent.class)))),
                                TypeInformation.get(DcsEvent.class))

                        // Default MongoSerializationSchema
                        //                .withSinkConnectorSetup(
                        //                        (sinkConnector, config) ->
                        //                                sinkConnector.withSerializationAdapter(
                        //                                        new MongoSerializationAdapter<>(
                        //                                                (input, ctx) -> {
                        //                                                    try {
                        //                                                        return new
                        // InsertOneModel<>(BsonDocumentParser.parse(input));
                        //                                                    } catch
                        // (IllegalAccessException e) {
                        //                                                        throw new
                        // RuntimeException(e);
                        //                                                    }
                        //                                                },
                        //
                        // TypeInformation.get(Record.class))),
                        //                        TypeInformation.get(Record.class))

                        .withSinkConnectorSetup(
                                (sinkConnector, config) ->
                                        sinkConnector.setSerializationAdapter(
                                                new MongoSerializationAdapter<>(
                                                        (input, ctx) ->
                                                                new UpdateManyModel<>(
                                                                        Filters.eq(
                                                                                "customer_id",
                                                                                "test"),
                                                                        Updates.set(
                                                                                "update_test",
                                                                                "success")),
                                                        TypeInformation.get(Record.class))),
                                TypeInformation.get(Record.class))
                        .build();

        (new MongoSink()).run(args, initializer);
    }

    @Override
    protected void execute(FlinkStreamingContext ctx) throws FlinkException {
        DataStreamSource<SensorMap> mapSourceStream =
                ctx.getSourceDataStream(TypeInformation.get(SensorMap.class));

        SingleOutputStreamOperator<Record> stream =
                ctx.getSourceDataStream(TypeInformation.get(DcsEvent.class))
                        .connect(mapSourceStream.broadcast(SENSOR_MAP_DESC))
                        .process(new TagProductionLineToDcs(SENSOR_MAP_DESC));

        FlinkDataStream.ofDataStream(stream, ctx).sink();
    }
}
