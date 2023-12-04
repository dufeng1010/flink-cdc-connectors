/*
 * Copyright 2023 Ververica Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.cdc.connectors.mysql.factory;

import org.apache.flink.table.api.ValidationException;

import com.ververica.cdc.common.annotation.Internal;
import com.ververica.cdc.common.configuration.ConfigOption;
import com.ververica.cdc.common.configuration.Configuration;
import com.ververica.cdc.common.event.TableId;
import com.ververica.cdc.common.factories.DataSourceFactory;
import com.ververica.cdc.common.factories.Factory;
import com.ververica.cdc.common.schema.Selectors;
import com.ververica.cdc.common.source.DataSource;
import com.ververica.cdc.connectors.mysql.source.MySqlDataSource;
import com.ververica.cdc.connectors.mysql.source.config.MySqlSourceConfig;
import com.ververica.cdc.connectors.mysql.source.config.MySqlSourceConfigFactory;
import com.ververica.cdc.connectors.mysql.source.config.ServerIdRange;
import com.ververica.cdc.connectors.mysql.source.offset.BinlogOffset;
import com.ververica.cdc.connectors.mysql.source.offset.BinlogOffsetBuilder;
import com.ververica.cdc.connectors.mysql.table.StartupOptions;
import com.ververica.cdc.connectors.mysql.utils.MySqlSchemaUtils;
import com.ververica.cdc.connectors.mysql.utils.OptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.CHUNK_KEY_EVEN_DISTRIBUTION_FACTOR_LOWER_BOUND;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.CHUNK_KEY_EVEN_DISTRIBUTION_FACTOR_UPPER_BOUND;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.CHUNK_META_GROUP_SIZE;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.CONNECTION_POOL_SIZE;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.CONNECT_MAX_RETRIES;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.CONNECT_TIMEOUT;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.HEARTBEAT_INTERVAL;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.HOSTNAME;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.PASSWORD;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.PORT;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.SCAN_INCREMENTAL_CLOSE_IDLE_READER_ENABLED;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.SCAN_INCREMENTAL_SNAPSHOT_CHUNK_SIZE;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.SCAN_SNAPSHOT_FETCH_SIZE;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.SCAN_STARTUP_MODE;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.SCAN_STARTUP_SPECIFIC_OFFSET_FILE;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.SCAN_STARTUP_SPECIFIC_OFFSET_GTID_SET;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.SCAN_STARTUP_SPECIFIC_OFFSET_POS;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.SCAN_STARTUP_SPECIFIC_OFFSET_SKIP_EVENTS;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.SCAN_STARTUP_SPECIFIC_OFFSET_SKIP_ROWS;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.SCAN_STARTUP_TIMESTAMP_MILLIS;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.SCHEMA_CHANGE_ENABLED;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.SERVER_ID;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.SERVER_TIME_ZONE;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.TABLES;
import static com.ververica.cdc.connectors.mysql.source.MySqlDataSourceOptions.USERNAME;
import static com.ververica.cdc.connectors.mysql.source.utils.ObjectUtils.doubleCompare;
import static com.ververica.cdc.debezium.table.DebeziumOptions.getDebeziumProperties;
import static com.ververica.cdc.debezium.utils.JdbcUrlUtils.getJdbcProperties;
import static org.apache.flink.util.Preconditions.checkState;

/** A {@link Factory} to create {@link MySqlDataSource}. */
@Internal
public class MySqlDataSourceFactory implements DataSourceFactory {

    private static final Logger LOG = LoggerFactory.getLogger(MySqlDataSourceFactory.class);

    public static final String IDENTIFIER = "mysql";

    @Override
    public DataSource createDataSource(Context context) {
        final Configuration config = context.getFactoryConfiguration();
        String hostname = config.get(HOSTNAME);
        int port = config.get(PORT);

        String username = config.get(USERNAME);
        String password = config.get(PASSWORD);
        String tables = config.get(TABLES);

        String serverId = validateAndGetServerId(config);
        ZoneId serverTimeZone = getServerTimeZone(config);
        StartupOptions startupOptions = getStartupOptions(config);

        boolean includeSchemaChanges = config.get(SCHEMA_CHANGE_ENABLED);

        int fetchSize = config.get(SCAN_SNAPSHOT_FETCH_SIZE);
        int splitSize = config.get(SCAN_INCREMENTAL_SNAPSHOT_CHUNK_SIZE);
        int splitMetaGroupSize = config.get(CHUNK_META_GROUP_SIZE);

        double distributionFactorUpper = config.get(CHUNK_KEY_EVEN_DISTRIBUTION_FACTOR_UPPER_BOUND);
        double distributionFactorLower = config.get(CHUNK_KEY_EVEN_DISTRIBUTION_FACTOR_LOWER_BOUND);

        boolean closeIdleReaders = config.get(SCAN_INCREMENTAL_CLOSE_IDLE_READER_ENABLED);

        Duration heartbeatInterval = config.get(HEARTBEAT_INTERVAL);
        Duration connectTimeout = config.get(CONNECT_TIMEOUT);
        int connectMaxRetries = config.get(CONNECT_MAX_RETRIES);
        int connectionPoolSize = config.get(CONNECTION_POOL_SIZE);

        validateIntegerOption(SCAN_INCREMENTAL_SNAPSHOT_CHUNK_SIZE, splitSize, 1);
        validateIntegerOption(CHUNK_META_GROUP_SIZE, splitMetaGroupSize, 1);
        validateIntegerOption(SCAN_SNAPSHOT_FETCH_SIZE, fetchSize, 1);
        validateIntegerOption(CONNECTION_POOL_SIZE, connectionPoolSize, 1);
        validateIntegerOption(CONNECT_MAX_RETRIES, connectMaxRetries, 0);
        validateDistributionFactorUpper(distributionFactorUpper);
        validateDistributionFactorLower(distributionFactorLower);

        Map<String, String> configMap = config.toMap();
        OptionUtils.printOptions(IDENTIFIER, config.toMap());

        MySqlSourceConfigFactory configFactory =
                new MySqlSourceConfigFactory()
                        .hostname(hostname)
                        .port(port)
                        .username(username)
                        .password(password)
                        .databaseList(".*")
                        .tableList(".*")
                        .startupOptions(startupOptions)
                        .serverId(serverId)
                        .serverTimeZone(serverTimeZone.getId())
                        .fetchSize(fetchSize)
                        .splitSize(splitSize)
                        .splitMetaGroupSize(splitMetaGroupSize)
                        .distributionFactorLower(distributionFactorLower)
                        .distributionFactorUpper(distributionFactorUpper)
                        .heartbeatInterval(heartbeatInterval)
                        .connectTimeout(connectTimeout)
                        .connectMaxRetries(connectMaxRetries)
                        .connectionPoolSize(connectionPoolSize)
                        .closeIdleReaders(closeIdleReaders)
                        .includeSchemaChanges(includeSchemaChanges)
                        .debeziumProperties(getDebeziumProperties(configMap))
                        .jdbcProperties(getJdbcProperties(configMap));

        Selectors selectors = new Selectors.SelectorsBuilder().includeTables(tables).build();
        String[] capturedTables = getTableList(configFactory.createConfig(0), selectors);
        if (capturedTables.length == 0) {
            throw new IllegalArgumentException(
                    "Cannot find any table by the option 'tables' = " + tables);
        }
        configFactory.tableList(capturedTables);

        return new MySqlDataSource(configFactory);
    }

    @Override
    public Set<ConfigOption<?>> requiredOptions() {
        Set<ConfigOption<?>> options = new HashSet<>();
        options.add(HOSTNAME);
        options.add(USERNAME);
        options.add(PASSWORD);
        options.add(TABLES);
        return options;
    }

    @Override
    public Set<ConfigOption<?>> optionalOptions() {
        Set<ConfigOption<?>> options = new HashSet<>();
        options.add(PORT);
        options.add(SERVER_TIME_ZONE);
        options.add(SERVER_ID);
        options.add(SCAN_STARTUP_MODE);
        options.add(SCAN_STARTUP_SPECIFIC_OFFSET_FILE);
        options.add(SCAN_STARTUP_SPECIFIC_OFFSET_POS);
        options.add(SCAN_STARTUP_SPECIFIC_OFFSET_GTID_SET);
        options.add(SCAN_STARTUP_SPECIFIC_OFFSET_SKIP_EVENTS);
        options.add(SCAN_STARTUP_SPECIFIC_OFFSET_SKIP_ROWS);
        options.add(SCAN_STARTUP_TIMESTAMP_MILLIS);
        options.add(SCAN_INCREMENTAL_SNAPSHOT_CHUNK_SIZE);
        options.add(CHUNK_META_GROUP_SIZE);
        options.add(SCAN_SNAPSHOT_FETCH_SIZE);
        options.add(CONNECT_TIMEOUT);
        options.add(CONNECTION_POOL_SIZE);
        options.add(CHUNK_KEY_EVEN_DISTRIBUTION_FACTOR_UPPER_BOUND);
        options.add(CHUNK_KEY_EVEN_DISTRIBUTION_FACTOR_LOWER_BOUND);
        options.add(CONNECT_MAX_RETRIES);
        options.add(SCAN_INCREMENTAL_CLOSE_IDLE_READER_ENABLED);
        options.add(HEARTBEAT_INTERVAL);
        options.add(SCHEMA_CHANGE_ENABLED);
        return options;
    }

    @Override
    public String identifier() {
        return IDENTIFIER;
    }

    private static final String SCAN_STARTUP_MODE_VALUE_INITIAL = "initial";
    private static final String SCAN_STARTUP_MODE_VALUE_EARLIEST = "earliest-offset";
    private static final String SCAN_STARTUP_MODE_VALUE_LATEST = "latest-offset";
    private static final String SCAN_STARTUP_MODE_VALUE_SPECIFIC_OFFSET = "specific-offset";
    private static final String SCAN_STARTUP_MODE_VALUE_TIMESTAMP = "timestamp";

    private static String[] getTableList(MySqlSourceConfig sourceConfig, Selectors selectors) {
        return MySqlSchemaUtils.listTables(sourceConfig, null).stream()
                .filter(selectors::isMatch)
                .map(TableId::toString)
                .toArray(String[]::new);
    }

    private static StartupOptions getStartupOptions(Configuration config) {
        String modeString = config.get(SCAN_STARTUP_MODE);

        switch (modeString.toLowerCase()) {
            case SCAN_STARTUP_MODE_VALUE_INITIAL:
                return StartupOptions.initial();

            case SCAN_STARTUP_MODE_VALUE_LATEST:
                return StartupOptions.latest();

            case SCAN_STARTUP_MODE_VALUE_EARLIEST:
                return StartupOptions.earliest();

            case SCAN_STARTUP_MODE_VALUE_SPECIFIC_OFFSET:
                validateSpecificOffset(config);
                return getSpecificOffset(config);

            case SCAN_STARTUP_MODE_VALUE_TIMESTAMP:
                return StartupOptions.timestamp(config.get(SCAN_STARTUP_TIMESTAMP_MILLIS));

            default:
                throw new ValidationException(
                        String.format(
                                "Invalid value for option '%s'. Supported values are [%s, %s, %s, %s, %s], but was: %s",
                                SCAN_STARTUP_MODE.key(),
                                SCAN_STARTUP_MODE_VALUE_INITIAL,
                                SCAN_STARTUP_MODE_VALUE_LATEST,
                                SCAN_STARTUP_MODE_VALUE_EARLIEST,
                                SCAN_STARTUP_MODE_VALUE_SPECIFIC_OFFSET,
                                SCAN_STARTUP_MODE_VALUE_TIMESTAMP,
                                modeString));
        }
    }

    private static void validateSpecificOffset(Configuration config) {
        Optional<String> gtidSet = config.getOptional(SCAN_STARTUP_SPECIFIC_OFFSET_GTID_SET);
        Optional<String> binlogFilename = config.getOptional(SCAN_STARTUP_SPECIFIC_OFFSET_FILE);
        Optional<Long> binlogPosition = config.getOptional(SCAN_STARTUP_SPECIFIC_OFFSET_POS);
        if (!gtidSet.isPresent() && !(binlogFilename.isPresent() && binlogPosition.isPresent())) {
            throw new ValidationException(
                    String.format(
                            "Unable to find a valid binlog offset. Either %s, or %s and %s are required.",
                            SCAN_STARTUP_SPECIFIC_OFFSET_GTID_SET.key(),
                            SCAN_STARTUP_SPECIFIC_OFFSET_FILE.key(),
                            SCAN_STARTUP_SPECIFIC_OFFSET_POS.key()));
        }
    }

    private static StartupOptions getSpecificOffset(Configuration config) {
        BinlogOffsetBuilder offsetBuilder = BinlogOffset.builder();

        // GTID set
        config.getOptional(SCAN_STARTUP_SPECIFIC_OFFSET_GTID_SET)
                .ifPresent(offsetBuilder::setGtidSet);

        // Binlog file + pos
        Optional<String> binlogFilename = config.getOptional(SCAN_STARTUP_SPECIFIC_OFFSET_FILE);
        Optional<Long> binlogPosition = config.getOptional(SCAN_STARTUP_SPECIFIC_OFFSET_POS);
        if (binlogFilename.isPresent() && binlogPosition.isPresent()) {
            offsetBuilder.setBinlogFilePosition(binlogFilename.get(), binlogPosition.get());
        } else {
            offsetBuilder.setBinlogFilePosition("", 0);
        }

        config.getOptional(SCAN_STARTUP_SPECIFIC_OFFSET_SKIP_EVENTS)
                .ifPresent(offsetBuilder::setSkipEvents);
        config.getOptional(SCAN_STARTUP_SPECIFIC_OFFSET_SKIP_ROWS)
                .ifPresent(offsetBuilder::setSkipRows);
        return StartupOptions.specificOffset(offsetBuilder.build());
    }

    private String validateAndGetServerId(Configuration configuration) {
        final String serverIdValue = configuration.get(SERVER_ID);
        if (serverIdValue != null) {
            // validation
            try {
                ServerIdRange.from(serverIdValue);
            } catch (Exception e) {
                throw new ValidationException(
                        String.format(
                                "The value of option 'server-id' is invalid: '%s'", serverIdValue),
                        e);
            }
        }
        return serverIdValue;
    }

    /** Checks the value of given integer option is valid. */
    private void validateIntegerOption(
            ConfigOption<Integer> option, int optionValue, int exclusiveMin) {
        checkState(
                optionValue > exclusiveMin,
                String.format(
                        "The value of option '%s' must larger than %d, but is %d",
                        option.key(), exclusiveMin, optionValue));
    }

    /** Checks the value of given evenly distribution factor upper bound is valid. */
    private void validateDistributionFactorUpper(double distributionFactorUpper) {
        checkState(
                doubleCompare(distributionFactorUpper, 1.0d) >= 0,
                String.format(
                        "The value of option '%s' must larger than or equals %s, but is %s",
                        CHUNK_KEY_EVEN_DISTRIBUTION_FACTOR_UPPER_BOUND.key(),
                        1.0d,
                        distributionFactorUpper));
    }

    /** Checks the value of given evenly distribution factor lower bound is valid. */
    private void validateDistributionFactorLower(double distributionFactorLower) {
        checkState(
                doubleCompare(distributionFactorLower, 0.0d) >= 0
                        && doubleCompare(distributionFactorLower, 1.0d) <= 0,
                String.format(
                        "The value of option '%s' must between %s and %s inclusively, but is %s",
                        CHUNK_KEY_EVEN_DISTRIBUTION_FACTOR_LOWER_BOUND.key(),
                        0.0d,
                        1.0d,
                        distributionFactorLower));
    }

    /** Replaces the default timezone placeholder with session timezone, if applicable. */
    private static ZoneId getServerTimeZone(Configuration config) {
        final String serverTimeZone = config.get(SERVER_TIME_ZONE);
        if (serverTimeZone != null) {
            return ZoneId.of(serverTimeZone);
        } else {
            LOG.warn(
                    "{} is not set, which might cause data inconsistencies for time-related fields.",
                    SERVER_TIME_ZONE.key());
            return ZoneId.systemDefault();
        }
    }
}
