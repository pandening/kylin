/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kylin.job.hadoop.cube;

import java.io.IOException;

import org.apache.commons.cli.Options;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.RegionLocator;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.mapreduce.HFileOutputFormat2;
import org.apache.hadoop.hbase.mapreduce.KeyValueSortReducer;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.partition.TotalOrderPartitioner;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.ToolRunner;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.persistence.HBaseConnection;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.CubeManager;
import org.apache.kylin.job.constant.BatchConstants;
import org.apache.kylin.job.hadoop.AbstractHadoopJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author George Song (ysong1)
 */
public class CubeHFileJob extends AbstractHadoopJob {

    protected static final Logger logger = LoggerFactory.getLogger(CubeHFileJob.class);

    public int run(String[] args) throws Exception {
        Options options = new Options();
        Connection connection = null;
        Table table = null;

        try {
            options.addOption(OPTION_JOB_NAME);
            options.addOption(OPTION_CUBE_NAME);
            options.addOption(OPTION_PARTITION_FILE_PATH);
            options.addOption(OPTION_INPUT_PATH);
            options.addOption(OPTION_OUTPUT_PATH);
            options.addOption(OPTION_HTABLE_NAME);
            parseOptions(options, args);

            Path partitionFilePath = new Path(getOptionValue(OPTION_PARTITION_FILE_PATH));

            Path output = new Path(getOptionValue(OPTION_OUTPUT_PATH));
            String cubeName = getOptionValue(OPTION_CUBE_NAME).toUpperCase();

            CubeManager cubeMgr = CubeManager.getInstance(KylinConfig.getInstanceFromEnv());

            CubeInstance cube = cubeMgr.getCube(cubeName);
            job = Job.getInstance(getConf(), getOptionValue(OPTION_JOB_NAME));

            setJobClasspath(job);

            addInputDirs(getOptionValue(OPTION_INPUT_PATH), job);
            FileOutputFormat.setOutputPath(job, output);

            job.setInputFormatClass(SequenceFileInputFormat.class);
            job.setMapperClass(CubeHFileMapper.class);
            job.setReducerClass(KeyValueSortReducer.class);

            // set job configuration
            job.getConfiguration().set(BatchConstants.CFG_CUBE_NAME, cubeName);
            Configuration conf = HBaseConfiguration.create(getConf());
            // add metadata to distributed cache
            attachKylinPropsAndMetadata(cube, job.getConfiguration());

            String tableName = getOptionValue(OPTION_HTABLE_NAME).toUpperCase();
            connection = HBaseConnection.get();
            table = connection.getTable(TableName.valueOf(tableName));
            RegionLocator regionLocator = connection.getRegionLocator(TableName.valueOf(tableName));


            //Automatic config !
            HFileOutputFormat2.configureIncrementalLoad(job, table, regionLocator);
            reconfigurePartitions(conf, partitionFilePath);


            // set block replication to 3 for hfiles
            conf.set(DFSConfigKeys.DFS_REPLICATION_KEY, "3");

            this.deletePath(job.getConfiguration(), output);

            return waitForCompletion(job);
        } catch (Exception e) {
            logger.error("error in CubeHFileJob", e);
            printUsage(options);
            throw e;
        } finally {
            IOUtils.closeQuietly(table);
            if (job != null)
                cleanupTempConfFile(job.getConfiguration());
        }
    }

    /**
     * Check if there's partition files for hfile, if yes replace the table splits, to make the job more reducers
     * @param conf the job configuration
     * @param path the hfile partition file
     * @throws IOException
     */
    @SuppressWarnings("deprecation")
    private void reconfigurePartitions(Configuration conf, Path path) throws IOException {
        FileSystem fs = path.getFileSystem(conf);
        if (fs.exists(path)) {
            try (SequenceFile.Reader reader = new SequenceFile.Reader(fs, path, conf)) {
                int partitionCount = 0;
                Writable key = (Writable) ReflectionUtils.newInstance(reader.getKeyClass(), conf);
                Writable value = (Writable) ReflectionUtils.newInstance(reader.getValueClass(), conf);
                while (reader.next(key, value)) {
                    partitionCount++;
                }
                TotalOrderPartitioner.setPartitionFile(job.getConfiguration(), path);
                // The reduce tasks should be one more than partition keys
                job.setNumReduceTasks(partitionCount+1);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        int exitCode = ToolRunner.run(new CubeHFileJob(), args);
        System.exit(exitCode);
    }

}