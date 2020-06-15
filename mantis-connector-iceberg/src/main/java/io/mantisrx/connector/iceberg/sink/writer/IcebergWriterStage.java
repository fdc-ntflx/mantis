/*
 * Copyright 2020 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mantisrx.connector.iceberg.sink.writer;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import io.mantisrx.connector.iceberg.sink.writer.config.WriterConfig;
import io.mantisrx.connector.iceberg.sink.writer.config.WriterProperties;
import io.mantisrx.connector.iceberg.sink.writer.metrics.WriterMetrics;
import io.mantisrx.runtime.Context;
import io.mantisrx.runtime.ScalarToScalar;
import io.mantisrx.runtime.WorkerInfo;
import io.mantisrx.runtime.computation.ScalarComputation;
import io.mantisrx.runtime.parameter.ParameterDefinition;
import io.mantisrx.runtime.parameter.type.IntParameter;
import io.mantisrx.runtime.parameter.type.StringParameter;
import io.mantisrx.runtime.parameter.validator.Validators;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;

/**
 * Processing stage which writes records to Iceberg through a backing file store.
 */
public class IcebergWriterStage implements ScalarComputation<Record, DataFile> {

    private static final Logger logger = LoggerFactory.getLogger(IcebergWriterStage.class);

    private final WriterMetrics metrics;
    private final Schema writerSchema;
    private final PartitionSpec partitionSpec;

    private Transformer transformer;

    /**
     * Returns a config for this stage which has encoding/decoding semantics and parameter definitions.
     * <p>
     * TODO: Avro codec.
     */
    public static ScalarToScalar.Config<Record, DataFile> config() {
        return new ScalarToScalar.Config<Record, DataFile>()
                .description("")
//                .codec(JacksonCodecs.mapStringObject())
                .withParameters(parameters());
    }

    /**
     * Returns a list of parameter definitions for this stage.
     */
    public static List<ParameterDefinition<?>> parameters() {
        return Arrays.asList(
                new IntParameter().name(WriterProperties.WRITER_ROW_GROUP_SIZE)
                        .description(WriterProperties.WRITER_ROW_GROUP_SIZE_DESCRIPTION)
                        .validator(Validators.alwaysPass())
                        .defaultValue(WriterProperties.WRITER_ROW_GROUP_SIZE_DEFAULT)
                        .build(),
                new StringParameter().name(WriterProperties.WRITER_FLUSH_FREQUENCY_BYTES)
                        .description(WriterProperties.WRITER_FLUSH_FREQUENCY_BYTES_DESCRIPTION)
                        .validator(Validators.alwaysPass())
                        .defaultValue(WriterProperties.WRITER_FLUSH_FREQUENCY_BYTES_DEFAULT)
                        .build(),
                new StringParameter().name(WriterProperties.WRITER_FILE_FORMAT)
                        .description(WriterProperties.WRITER_FILE_FORMAT_DESCRIPTION)
                        .validator(Validators.alwaysPass())
                        .defaultValue(WriterProperties.WRITER_FILE_FORMAT_DEFAULT)
                        .build(),
                new StringParameter().name(WriterProperties.WRITER_PARTITION_KEY)
                        .description(WriterProperties.WRITER_PARTITION_KEY_DESCRIPTION)
                        .validator(Validators.alwaysPass())
                        .defaultValue(WriterProperties.WRITER_FILE_FORMAT_DEFAULT)
                        .build()
        );
    }

    public IcebergWriterStage(Schema writerSchema, PartitionSpec partitionSpec) {
        this.metrics = new WriterMetrics();
        this.writerSchema = writerSchema;
        this.partitionSpec = partitionSpec;
    }

    @Override
    public void init(Context context) {
        WriterConfig config = new WriterConfig(context.getParameters(), new Configuration());
        Catalog catalog = context.getServiceLocator().service(Catalog.class);
        // TODO: Get namespace and name from config.
        TableIdentifier id = TableIdentifier.of("namespace", "name");
        Table table = catalog.loadTable(id);
        WorkerInfo workerInfo = context.getWorkerInfo();
        IcebergWriter writer = new UnpartitionedIcebergWriter(metrics, config, workerInfo, table, partitionSpec);
        transformer = new Transformer(config, writer);
    }

    @Override
    public Observable<DataFile> call(Context context, Observable<Record> recordObservable) {
        return recordObservable.compose(transformer);
    }

    /**
     *
     */
    public static class Transformer implements Observable.Transformer<Record, DataFile> {

        private final WriterConfig config;
        private final IcebergWriter writer;

        public Transformer(WriterConfig config, IcebergWriter writer) {
            this.config = config;
            this.writer = writer;
        }

        /**
         *
         */
        @Override
        public Observable<DataFile> call(Observable<Record> source) {
            return source
                    .scan(new Counter(config.getWriterRowGroupSize()), (counter, record) -> {
                        writer.write(record);
                        counter.increment();
                        return counter;
                    })
                    .filter(Counter::shouldReset)
                    .map(counter -> {
                        try {
                            DataFile dataFile = writer.close();
                            counter.reset();
                            return dataFile;
                        } catch (IOException e) {
                            throw rx.exceptions.Exceptions.propagate(e);
                        }
                    })
                    .doOnNext(dataFile -> {
                    })
                    .doOnError(throwable -> {
                    });
        }
    }

    private static class Counter {

        private final int threshold;
        private int counter;

        Counter(int threshold) {
            this.threshold = threshold;
            this.counter = 0;
        }

        void increment() {
            counter++;
        }

        void reset() {
            counter = 0;
        }

        boolean shouldReset() {
            return counter >= threshold;
        }
    }
}
