
package com.citic.source.canal.trans;

import static com.citic.sink.canal.KafkaSinkConstants.DEFAULT_TOPIC_OVERRIDE_HEADER;
import static com.citic.sink.canal.KafkaSinkConstants.SCHEMA_NAME;
import static com.citic.source.canal.CanalSourceConstants.GSON;
import static com.citic.source.canal.CanalSourceConstants.META_DATA;
import static com.citic.source.canal.CanalSourceConstants.META_FIELD_AGENT;
import static com.citic.source.canal.CanalSourceConstants.META_FIELD_FROM;
import static com.citic.source.canal.CanalSourceConstants.META_SPLIT_ID;
import static com.citic.source.canal.CanalSourceConstants.META_TRANS_ID;
import static com.citic.source.canal.CanalSourceConstants.SUPPORT_TIME_FORMAT;
import static com.citic.source.canal.CanalSourceConstants.TOKEN_TYPE;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.citic.helper.AgentCounter;
import com.citic.helper.AgentCounter.AgentCounterKey;
import com.citic.helper.FlowCounter;
import com.citic.helper.FlowCounter.FlowCounterKey;
import com.citic.helper.MyRuntimeException;
import com.citic.helper.SchemaCache;
import com.citic.helper.Utility;
import com.citic.source.canal.AbstractEntrySqlHandler.Avro;
import com.citic.source.canal.CanalConf;
import com.citic.source.canal.core.EntryConverterInterface;
import com.citic.source.canal.core.EntrySqlHandlerInterface;
import com.citic.source.canal.core.TransDataHandlerInterface;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.RegexHashBasedTable;
import com.google.gson.reflect.TypeToken;
import com.google.protobuf.InvalidProtocolBufferException;
import com.twitter.bijection.Injection;
import com.twitter.bijection.avro.GenericAvroCodecs;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.flume.Event;
import org.apache.flume.event.EventBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The type Entry converter.
 */
public class EntryConverter implements EntryConverterInterface {

    private static final Logger LOGGER = LoggerFactory.getLogger(EntryConverter.class);
    private static final Type LIST_TOKEN_TYPE = new TypeToken<List<Map<String, String>>>() {
    }.getType();
    private static final String NOT_SET_FIELD = "__notSet";

    private final SimpleDateFormat dateFormat = new SimpleDateFormat(SUPPORT_TIME_FORMAT);

    private final EntrySqlHandlerInterface sqlHandler;
    private final TransDataHandlerInterface dataHandler;
    private final List<Map<String, String>> transDataList;
    private final CanalConf canalConf;
    private final boolean userAvro;

    // topic -> schema
    private final Map<String, String> topicToSchemaMap = Maps.newHashMap();
    private final RegexHashBasedTable<String, Boolean> removeFilterTable = RegexHashBasedTable
        .create();
    private String transId = "first_start_unknown_trans_id";
    private int splitId = 0;

    private final List<String> attrList;

    /**
     * Instantiates a new Entry converter.
     *
     * @param canalConf the canal conf
     */
    public EntryConverter(boolean useAvro, CanalConf canalConf) {
        this.canalConf = canalConf;
        this.userAvro = useAvro;
        this.sqlHandler = new Avro();
        this.transDataList = Lists.newArrayList();

        this.attrList = Lists
            .newArrayList(META_DATA, META_TRANS_ID, META_SPLIT_ID, META_FIELD_AGENT,
                META_FIELD_FROM);

        if (this.userAvro) {
            splitTableToTopicMap(canalConf.getTableToTopicMap());
        }
        splitTableFieldsFilter(canalConf.getTableFieldsFilter());

        BiFunction<String, String, Boolean> removeColumnFilterFun = removeFilterTable::contains;
        Function<String, Boolean> removeRowFilterFunc = tableKey ->
            removeFilterTable.contains(tableKey, NOT_SET_FIELD);

        this.dataHandler = new DataHandler(canalConf, removeRowFilterFunc,
            removeColumnFilterFun);
    }

    /*
     * 处理 Event Header 获取数据的 topic
     * */
    private Map<String, String> handleRowDataHeader(String topic) {
        Map<String, String> header = Maps.newHashMap();
        header.put(DEFAULT_TOPIC_OVERRIDE_HEADER, topic);
        return header;
    }

    /*
     * 处理行数据，并添加其他字段信息
     * */
    private Map<String, Object> handleRowData(List<Map<String, String>> transDataList,
        String transId, int splitId) {
        Map<String, Object> eventMap = Maps.newHashMap();

        if (this.userAvro) {
            String transDataListString = GSON.toJson(transDataList, LIST_TOKEN_TYPE);
            eventMap.put(META_DATA, transDataListString);
        } else {
            eventMap.put(META_DATA, transDataList);
        }

        eventMap.put(META_TRANS_ID, transId == null ? "" : transId);
        eventMap.put(META_SPLIT_ID, String.valueOf(splitId));
        eventMap.put(META_FIELD_AGENT, canalConf.getAgentIpAddress());
        eventMap.put(META_FIELD_FROM, canalConf.getFromDbIp());
        return eventMap;
    }

    private void splitTableToTopicMap(String tableToTopicMap) {
        Preconditions.checkArgument(!Strings.isNullOrEmpty(tableToTopicMap),
            "tableToTopicMap cannot empty");
        // test.test:test123:schema1;test.test1:test234:schema2
        Splitter.on(';')
            .omitEmptyStrings()
            .trimResults()
            .split(tableToTopicMap)
            .forEach(item -> {
                String[] result = item.split(":");

                Preconditions.checkArgument(result.length == 3,
                    "tableToTopicMap format incorrect eg: db.tbl1:topic1:schema1");
                Preconditions.checkArgument(!Strings.isNullOrEmpty(result[0].trim()),
                    "db.table cannot empty");
                Preconditions.checkArgument(!Strings.isNullOrEmpty(result[1].trim()),
                    "topic cannot empty");
                Preconditions.checkArgument(!Strings.isNullOrEmpty(result[2].trim()),
                    "schema cannot empty");

                // topic -> avro schema
                this.topicToSchemaMap.put(result[1].trim(), result[2].trim());
            });
    }

    /*
     * 对库，表和字段排除进行解析
     * */
    private void splitTableFieldsFilter(String tableFieldsFilter) {
        if (Strings.isNullOrEmpty(tableFieldsFilter)) {
            return;
        }

        // test\\..*;test1.test2;test1.test3:id,name
        Splitter.on(';')
            .omitEmptyStrings()
            .trimResults()
            .split(tableFieldsFilter)
            .forEach(item -> {
                if (item.contains(":")) { // 字段过滤
                    String[] result = item.split(":");
                    Preconditions
                        .checkArgument(result.length == 2,
                            "tableFieldsFilter format incorrect eg: test1\\..*;db.tbl1:id,name");
                    String table = result[0].trim();
                    String fields = result[1].trim();

                    for (String field : fields.split(",")) {
                        removeFilterTable.put(table, field.trim(), true);
                    }
                } else { // 库，表过滤
                    removeFilterTable.put(item, NOT_SET_FIELD, true);
                }
            });
    }

    private Event dataToEvent(Map<String, Object> eventData,
        Map<String, String> eventHeader,
        String topic) {

        String schemaName = topicToSchemaMap.get(topic);
        Schema schema = SchemaCache.getSchema(this.attrList, schemaName);

        GenericRecord avroRecord = new GenericData.Record(schema);

        for (String fieldStr : this.attrList) {
            avroRecord.put(fieldStr, eventData.getOrDefault(fieldStr, ""));
        }

        // 用于sink解析
        eventHeader.put(SCHEMA_NAME, schemaName);
        LOGGER.debug("event data: {}", avroRecord);
        LOGGER.debug("event header: {}", eventHeader);

        Injection<GenericRecord, byte[]> recordInjection = GenericAvroCodecs.toBinary(schema);
        byte[] eventBody = recordInjection.apply(avroRecord);
        return EventBuilder.withBody(eventBody, eventHeader);
    }

    private Event jsonDataToEvent(Map<String, Object> eventData,
        Map<String, String> eventHeader) {
        byte[] eventBody = GSON.toJson(eventData, TOKEN_TYPE).getBytes(Charset.forName("UTF-8"));
        LOGGER.debug("event data: {}", eventData);
        LOGGER.debug("event header: {}", eventHeader);
        return EventBuilder.withBody(eventBody, eventHeader);
    }


    private Event transDataToEvent(CanalEntry.Header tansEndHeader) {
        // 处理行数据
        Map<String, Object> eventData = handleRowData(this.transDataList, this.transId,
            this.splitId);

        // 全局事务封装只能配置全库全表, 获取第一个 topic
        String allTables = canalConf.getFilterTableList().get(0);
        String topic = canalConf.getTableTopic(allTables);

        Map<String, String> header = handleRowDataHeader(topic);

        if (!canalConf.isShutdownFlowCounter()) {
            doDataCount(topic, allTables, tansEndHeader, header);
        }

        if (this.userAvro) {
            return dataToEvent(eventData, header, topic);
        } else {
            return jsonDataToEvent(eventData, header);
        }
    }

    private void doDataCount(String topic, String tableKey, CanalEntry.Header entryHeader,
        Map<String, String> headerData) {
        String timeFieldValue = dateFormat.format(new Date(entryHeader.getExecuteTime()));
        // 事务封装不能确定单个表，因此传入空值
        FlowCounterKey flowCounterKey = FlowCounter
            .increment(topic, tableKey, canalConf.getFromDbIp(), timeFieldValue);
        Utility.putFlowCounterKeyToHeader(headerData, flowCounterKey);

        AgentCounterKey agentCounterKey = AgentCounter.increment(canalConf.getAgentIpAddress());
        Utility.putAgentCounterKeyToHeader(headerData, agentCounterKey);
    }


    private void handleTransactionEnd(CanalEntry.Entry entry, List<Event> events) {
        CanalEntry.TransactionEnd end;
        try {
            end = CanalEntry.TransactionEnd
                .parseFrom(entry.getStoreValue());
        } catch (InvalidProtocolBufferException e) {
            LOGGER.error(
                "parse transaction end event has an error , data:{}",  entry);
            throw new MyRuntimeException(
                "parse event has an error , data:" + entry.toString(), e);
        }

        if (!this.transDataList.isEmpty()) {
            Event transEvent = transDataToEvent(entry.getHeader());
            events.add(transEvent);
            this.transDataList.clear();
        }

        // 用上个事务的id作为数据的事务id，只要能确定唯一就ok
        this.transId = end.getTransactionId();
        this.splitId = 0;

        LOGGER.debug("TRANSACTIONEND transId:{}", this.transId);
    }

    private void handelRowData(CanalEntry.Entry entry, List<Event> events) {
        CanalEntry.RowChange rowChange;
        try {
            rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
        } catch (Exception e) {
            LOGGER.warn("parse row data event has an error , data:" + entry.toString(), e);
            throw new MyRuntimeException("parse event has an error , data:" + entry.toString(),
                e);
        }
        CanalEntry.EventType eventType = rowChange.getEventType();
        CanalEntry.Header eventHeader = entry.getHeader();

        // canal 在 QUERY 事件没有做表过滤
        if (eventType == CanalEntry.EventType.QUERY) {
            // do nothing
        } else if (rowChange.getIsDdl()) {
            // 只有 ddl 操作才记录 sql, 其他 insert update delete 不做sql记录操作
            events.add(this.sqlHandler.getSqlEvent(eventHeader, rowChange.getSql(), canalConf));
        } else {
            for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                Map<String, String> dataMap = this.dataHandler
                    .getDataMap(rowData, eventHeader, eventType);
                if (dataMap != null) {
                    transDataList.add(dataMap);
                }

                // 对大事务进行拆分
                if (this.transDataList.size() >= this.canalConf.getTransMaxSplitRowNum()) {
                    Event transEvent = transDataToEvent(entry.getHeader());
                    events.add(transEvent);
                    this.transDataList.clear();
                    this.splitId += 1;
                }

            }
        }
    }

    @Override
    public List<Event> convert(CanalEntry.Entry entry, CanalConf canalConf) {
        List<Event> events = new ArrayList<>();
        // 经过测试发现，EntryType.TRANSACTIONBEGIN 不会产生
        if (entry.getEntryType() == CanalEntry.EntryType.TRANSACTIONEND) {
            handleTransactionEnd(entry, events);
        } else if (entry.getEntryType() == CanalEntry.EntryType.ROWDATA) {
            handelRowData(entry, events);
        }
        return events;
    }
}
