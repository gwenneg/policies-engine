package org.hawkular.alerts.rest

import com.google.common.collect.Multimap
import groovy.json.JsonOutput
import io.quarkus.test.junit.QuarkusTest
import org.hawkular.alerts.api.json.GroupConditionsInfo
import org.hawkular.alerts.api.json.GroupMemberInfo
import org.hawkular.alerts.api.json.UnorphanMemberInfo
import org.hawkular.alerts.api.model.Severity
import org.hawkular.alerts.api.model.action.ActionDefinition
import org.hawkular.alerts.api.model.condition.Condition
import org.hawkular.alerts.api.model.condition.ThresholdCondition
import org.hawkular.alerts.api.model.dampening.Dampening
import org.hawkular.alerts.api.model.trigger.Mode
import org.hawkular.alerts.api.model.trigger.Trigger
import org.hawkular.alerts.log.MsgLogger
import org.hawkular.alerts.log.MsgLogging
import org.junit.jupiter.api.Test

import static org.hawkular.alerts.api.json.JsonUtil.fromJson
import static org.hawkular.alerts.api.json.JsonUtil.toJson
import static org.junit.jupiter.api.Assertions.*

/**
 * Triggers REST tests.
 *
 * @author Lucas Ponce
 */
@QuarkusTest
class TriggersITest extends AbstractQuarkusITestBase {

    static MsgLogger logger = MsgLogging.getMsgLogger(TriggersITest.class)

    @Test
    void createTrigger() {
        Trigger testTrigger = new Trigger("test-trigger-1", "No-Metric");

        // remove if it exists
        def resp = client.delete(path: "triggers/test-trigger-1")
        assert(200 == resp.status || 404 == resp.status)

        resp = client.post(path: "triggers", body: testTrigger)
        assertEquals(200, resp.status)

        // Should not be able to create the same triggerId again
        resp = client.post(path: "triggers", body: testTrigger)
        assertEquals(400, resp.status)

        resp = client.get(path: "triggers/test-trigger-1");
        assertEquals(200, resp.status)
        assertEquals("No-Metric", resp.data.name)

        testTrigger.setName("No-Metric-Modified")
        testTrigger.setSeverity(Severity.CRITICAL)
        resp = client.put(path: "triggers/test-trigger-1", body: testTrigger)
        assertEquals(200, resp.status)

        resp = client.get(path: "triggers/test-trigger-1")
        assertEquals(200, resp.status)
        assertEquals("No-Metric-Modified", resp.data.name)
        assertEquals("CRITICAL", resp.data.severity)

        resp = client.delete(path: "triggers/test-trigger-1")
        assertEquals(200, resp.status)

        // Test create w/o assigning a TriggerId, it should generate a UUID
        testTrigger.setId(null);
        resp = client.post(path: "triggers", body: testTrigger)
        assertEquals(200, resp.status)
        assertNotNull(resp.data.id)

        // Delete the trigger
        resp = client.delete(path: "triggers/" + resp.data.id)
        assertEquals(200, resp.status)
    }

    @Test
    void testGroupTrigger() {
        Trigger groupTrigger = new Trigger("group-trigger", "group-trigger");
        groupTrigger.setEnabled(false);

        // remove if it exists
        def resp = client.delete(path: "triggers/groups/group-trigger", query: [keepNonOrphans:false,keepOrphans:false])
        assert(200 == resp.status || 404 == resp.status)

        // create the group
        def groupTriggerBody = toJson(groupTrigger)
        resp = client.post(path: "triggers/groups", body: groupTriggerBody)
        assertEquals(200, resp.status)

        resp = client.get(path: "triggers/group-trigger")
        assertEquals(200, resp.status)
        def jsonOut = JsonOutput.toJson(resp.data)
        groupTrigger = fromJson(jsonOut, Trigger.class)
        assertEquals( true, groupTrigger.isGroup() );

        ThresholdCondition cond1 = new ThresholdCondition("group-trigger", Mode.FIRING, "DataId1-Token",
            ThresholdCondition.Operator.GT, 10.0);
        Map<String, Map<String, String>> dataIdMemberMap = new HashMap<>();
        GroupConditionsInfo groupConditionsInfo = new GroupConditionsInfo( cond1, dataIdMemberMap );

        resp = client.put(path: "triggers/groups/group-trigger/conditions/firing", body: groupConditionsInfo)
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size())

        // create member 1
        Map<String, String> dataId1Map = new HashMap<>(2);
        dataId1Map.put("DataId1-Token", "DataId1-Child1");
        GroupMemberInfo groupMemberInfo = new GroupMemberInfo("group-trigger", "member1", "member1", null, null, null,
            dataId1Map);
        resp = client.post(path: "triggers/groups/members", body: groupMemberInfo);
        assertEquals(200, resp.status)

        // create member 2
        dataId1Map.put("DataId1-Token", "DataId1-Child2");
        groupMemberInfo = new GroupMemberInfo("group-trigger", "member2", "member2", null, null, null, dataId1Map);
        resp = client.post(path: "triggers/groups/members", body: groupMemberInfo);
        assertEquals(200, resp.status)

        // orphan member2, it should no longer get updates
        resp = client.put(path: "triggers/groups/members/member2/orphan");
        assertEquals(200, resp.status)

        // add dampening to the group
        Dampening groupDampening = Dampening.forRelaxedCount("", "group-trigger", Mode.FIRING, 2, 4);
        resp = client.post(path: "triggers/groups/group-trigger/dampenings", body: groupDampening);
        assertEquals(200, resp.status)
        groupDampening = resp.data // get the updated dampeningId for use below

        // add tag to the group (via update)
        groupTrigger.addTag( "group-tname", "group-tvalue" );
        def groupTriggerJson = toJson(groupTrigger)
        resp = client.put(path: "triggers/groups/group-trigger", body: groupTriggerJson)
        assertEquals(200, resp.status)

        // add another condition to the group (only member1 is relevant, member2 is now an orphan)
        ThresholdCondition cond2 = new ThresholdCondition("group-trigger", Mode.FIRING, "DataId2-Token",
            ThresholdCondition.Operator.LT, 20.0);
        dataId1Map.clear();
        dataId1Map.put("member1", "DataId1-Child1");
        Map<String, String> dataId2Map = new HashMap<>(2);
        dataId2Map.put("member1", "DataId2-Child1");
        dataIdMemberMap.put("DataId1-Token", dataId1Map);
        dataIdMemberMap.put("DataId2-Token", dataId2Map);
        Collection<Condition> groupConditions = new ArrayList<>(2);
        groupConditions.add(cond1);
        groupConditions.add(cond2);
        groupConditionsInfo = new GroupConditionsInfo(groupConditions, dataIdMemberMap);
        resp = client.put(path: "triggers/groups/group-trigger/conditions/firing", body: groupConditionsInfo)
        assertEquals(200, resp.status)

        // update the group trigger
        groupTrigger.setAutoDisable( true );
        def body = toJson(groupTrigger)
        resp = client.put(path: "triggers/groups/group-trigger", body: body)
        assertEquals(200, resp.status)

        // update the group dampening to the group
        String did = groupDampening.getDampeningId();
        groupDampening = Dampening.forRelaxedCount("", "group-trigger", Mode.FIRING, 2, 6);
        resp = client.put(path: "triggers/groups/group-trigger/dampenings/" + did, body: groupDampening);
        assertEquals(200, resp.status)

        // query w/o orphans
        resp = client.get(path: "triggers/groups/group-trigger/members", query: [includeOrphans:"false"])
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size());

        jsonOut = JsonOutput.toJson(resp.data)
        Trigger[] memberTriggers = fromJson(jsonOut, Trigger[].class)

        Trigger member = memberTriggers[0]
        assertEquals( false, member.isOrphan() );
        assertEquals( "member1", member.getName() );

        // query w orphans
        resp = client.get(path: "triggers/groups/group-trigger/members", query: [includeOrphans:"true"])
        assertEquals(200, resp.status)
        assertEquals(2, resp.data.size());

        jsonOut = JsonOutput.toJson(resp.data)
        memberTriggers = fromJson(jsonOut, Trigger[].class)
        member = memberTriggers[0]
        Trigger orphanMember = memberTriggers[1]
        if ( member.isOrphan() ) {
            member = memberTriggers[1]
            orphanMember = memberTriggers[0]
        }
        assertEquals( "member1", member.getName() );
        assertEquals( false, member.isOrphan() );
        assertEquals( true, member.isAutoDisable());
        assertEquals( "member2", orphanMember.getName() );
        assertEquals( true, orphanMember.isOrphan() );
        assertEquals( false, orphanMember.isAutoDisable());

        // get the group trigger
        resp = client.get(path: "triggers/group-trigger")
        assertEquals(200, resp.status)
        jsonOut = JsonOutput.toJson(resp.data)
        groupTrigger = fromJson(jsonOut, Trigger.class)
        assertEquals( "group-trigger", groupTrigger.getName() );
        assertEquals( true, groupTrigger.isGroup() );
        assertEquals( true, groupTrigger.isAutoDisable());

        // get the group dampening
        resp = client.get(path: "triggers/group-trigger/dampenings")
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size());

        // check the group tags
        Multimap<String, String> groupTags = groupTrigger.getTags();
        assertEquals(1, groupTags.size());
        assertEquals("group-tvalue", groupTags.get("group-tname").iterator().next());

        // get the group conditions
        resp = client.get(path: "triggers/group-trigger/conditions")
        assertEquals(200, resp.status)
        assertEquals(2, resp.data.size());
        groupConditions = (Collection<Condition>)resp.data;
        Set<String> conditionDataIds = new HashSet<>();
        conditionDataIds.add(((ThresholdCondition) resp.data[0]).getDataId())
        conditionDataIds.add(((ThresholdCondition) resp.data[1]).getDataId())
        assertTrue(conditionDataIds.contains("DataId1-Token"))
        assertTrue(conditionDataIds.contains("DataId2-Token"))

        // get the member1 trigger
        resp = client.get(path: "triggers/member1")
        assertEquals(200, resp.status)

        jsonOut = JsonOutput.toJson(resp.data)
        def member1 = fromJson(jsonOut, Trigger.class)
        assertEquals( "member1", member1.getName() );
        assertEquals( true, member1.isMember() );
        assertEquals( true, member1.isAutoDisable());

        // check the member1 tag
        Multimap<String, String> memberTags = member1.getTags();
        assertEquals(1, memberTags.size());
        assertEquals("group-tvalue", memberTags.get("group-tname").iterator().next());

        // get the member1 dampening
        resp = client.get(path: "triggers/member1/dampenings")
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size());

        // get the member1 conditions
        resp = client.get(path: "triggers/member1/conditions")
        assertEquals(200, resp.status)
        assertEquals(2, resp.data.size());

        // get the member2 trigger
        resp = client.get(path: "triggers/member2")
        assertEquals(200, resp.status)

        jsonOut = JsonOutput.toJson(resp.data)
        def member2 = fromJson(jsonOut, Trigger.class)
        assertEquals( "member2", member2.getName() );
        assertEquals( true, member2.isMember() );
        assertEquals( false, member2.isAutoDisable());

        // check the member2 tag
        memberTags = member2.getTags();
        assertEquals(0, memberTags.size());

        // check the member2 dampening
        resp = client.get(path: "triggers/member2/dampenings")
        assertEquals(200, resp.status)
        assertEquals(0, resp.data.size());

        // get the member2 condition
        resp = client.get(path: "triggers/member2/conditions")
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size());

        // unorphan member2
        Map<String, String> dataIdMap = new HashMap<>(2);
        dataIdMap.put("DataId1-Token", "DataId1-Child2");
        dataIdMap.put("DataId2-Token", "DataId2-Child2");
        UnorphanMemberInfo unorphanMemberInfo = new UnorphanMemberInfo(null, null, dataIdMap);
        resp = client.put(path: "triggers/groups/members/member2/unorphan", body: unorphanMemberInfo);
        assertEquals(200, resp.status)

        // get the member2 trigger
        resp = client.get(path: "triggers/member2")
        assertEquals(200, resp.status)
        jsonOut = JsonOutput.toJson(resp.data)
        member2 = fromJson(jsonOut, Trigger.class)
        assertEquals( "member2", member2.getName() );
        assertEquals( false, member2.isOrphan() );
        assertEquals( true, member2.isAutoDisable());

        // check the member2 tag
        memberTags = member2.getTags();
        assertEquals(1, memberTags.size());
        assertEquals("group-tvalue", memberTags.get("group-tname").iterator().next());

        // check the member2 dampening
        resp = client.get(path: "triggers/member2/dampenings")
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size());

        // get the member2 condition
        resp = client.get(path: "triggers/member2/conditions")
        assertEquals(200, resp.status)
        assertEquals(2, resp.data.size());

        // delete group tag
        groupTrigger.getTags().clear();
        groupTriggerJson = toJson(groupTrigger)
        resp = client.put(path: "triggers/groups/group-trigger", body: groupTriggerJson)
        assertEquals(200, resp.status)

        // delete group dampening
        resp = client.delete(path: "triggers/groups/group-trigger/dampenings/" + did)
        assertEquals(200, resp.status)

        // delete group cond1 (done by re-setting conditions w/o the undesired condition)
        groupConditions.clear();
        groupConditions.add(cond2);
        logger.info(groupConditions.toString())
        assertEquals(1, groupConditions.size());
        dataId2Map.clear();
        dataId2Map.put("member1", "DataId2-Child1");
        dataId2Map.put("member2", "DataId2-Child2");
        dataIdMemberMap.clear();
        dataIdMemberMap.put("DataId2-Token", dataId2Map);
        groupConditionsInfo = new GroupConditionsInfo(groupConditions, dataIdMemberMap);
        resp = client.put(path: "triggers/groups/group-trigger/conditions/firing", body: groupConditionsInfo)
        assertEquals(200, resp.status)

        // get the group trigger
        resp = client.get(path: "triggers/group-trigger")
        assertEquals(200, resp.status)
        jsonOut = toJson(resp.data)
        groupTrigger = fromJson(jsonOut, Trigger.class)
        assertEquals( "group-trigger", groupTrigger.getName() );
        assertEquals( true, groupTrigger.isGroup() );
        assertEquals( true, groupTrigger.isAutoDisable());

        // check the group dampening
        groupTags = groupTrigger.getTags();
        assertEquals(0, groupTags.size());

        // check the group condition
        resp = client.get(path: "triggers/group-trigger/conditions")
        assertEquals(200, resp.status)
        assertEquals(1, resp.data.size());

        // query w/o orphans
        resp = client.get(path: "triggers/groups/group-trigger/members", query: [includeOrphans:"false"])
        assertEquals(200, resp.status)
        assertEquals(2, resp.data.size());
        def members = fromJson(toJson(resp.data), Trigger[].class)
        for(int i=0; i < resp.data.size(); ++i) {
            member = members[i]
            String name = member.getName();
            assertEquals( false, member.isOrphan() );
            assertEquals( true, member.isAutoDisable());
            resp = client.get(path: "triggers/" + name + "/dampenings")
            assertEquals(200, resp.status)
            assertEquals(0, resp.data.size());
            resp = client.get(path: "triggers/" + name + "/conditions")
            assertEquals(200, resp.status)
            assertEquals(1, resp.data.size());
        }

        // ensure a member trigger can be removed with the standard remove trigger
        resp = client.delete(path: "triggers/member2")
        assertEquals(200, resp.status)
        resp = client.get(path: "triggers/member2")
        assertEquals(404, resp.status)

        // ensure a group trigger can not be removed with the standard remove trigger
        resp = client.delete(path: "triggers/group-trigger")
        assertEquals(400, resp.status) // Changed here as it should return a 400 due is a bad argument, not an internal problem

        // remove group trigger
        resp = client.delete(path: "triggers/groups/group-trigger")
        assertEquals(200, resp.status)

        resp = client.get(path: "triggers/group-trigger")
        assertEquals(404, resp.status)
        resp = client.get(path: "triggers/member1")
        assertEquals(404, resp.status)
        resp = client.get(path: "triggers/member2")
        assertEquals(404, resp.status)
    }

    @Test
    void testTag() {
        Trigger testTrigger = new Trigger("test-trigger-1", "No-Metric");
        testTrigger.addTag("tname", "tvalue");

        // remove if it exists
        def resp = client.delete(path: "triggers/test-trigger-1")
        assert(200 == resp.status || 404 == resp.status)

        // create the test trigger
        def body = toJson(testTrigger)
        resp = client.post(path: "triggers", body: body)
        assertEquals(200, resp.status)

        // make sure the test trigger exists
        resp = client.get(path: "triggers/test-trigger-1");
        assertEquals(200, resp.status)
        assertEquals("No-Metric", resp.data.name)

        def jsonOut = JsonOutput.toJson(resp.data)
        def tagTrigger = fromJson(jsonOut, Trigger.class)
        Multimap<String, String> tags = tagTrigger.getTags()
        assertEquals("tvalue", tags.get("tname").iterator().next());

        resp = client.get(path: "triggers", query: [query:"tags.tname = 'tvalue'"] );
        jsonOut = JsonOutput.toJson(resp.data)
        Trigger[] tagTriggers2 = fromJson(jsonOut, Trigger[].class)
        assertEquals(200, resp.status)
        assertEquals("test-trigger-1", tagTriggers2[0].id)

        resp = client.get(path: "triggers", query: [query:"tags.tname matches '*'"] );
        assertEquals(200, resp.status)
        assertEquals("test-trigger-1", resp.data.iterator().next().id)

        resp = client.get(path: "triggers", query: [query:"tags.funky = 'funky'"] );
        assertEquals(200, resp.status)
        assertEquals(false, resp.data.iterator().hasNext())

        // delete the tag
        testTrigger.getTags().clear();
        resp = client.put(path: "triggers/test-trigger-1", body: testTrigger )
        assertEquals(200, resp.status)

        resp = client.get(path: "triggers", query: [query:"tags.tname = 'tvalue'"] );
        assertEquals(200, resp.status)
        assertEquals(0, resp.data.size())

        // delete the trigger
        resp = client.delete(path: "triggers/test-trigger-1")
        assertEquals(200, resp.status)
    }

    @Test
    void createFullTrigger() {
        // CREATE the action definition
        String actionPlugin = "notification"
        String actionId = "notification-to-admin";

        Map<String, String> actionProperties = new HashMap<>();
        actionProperties.put("endpoint_id", "1");

        ActionDefinition actionDefinition = new ActionDefinition(null, actionPlugin, actionId, actionProperties);

        def resp = client.post(path: "actions", body: actionDefinition)
        assert(200 == resp.status || 400 == resp.status)

        String jsonTrigger = "{\n" +
                "      \"trigger\":{\n" +
                "        \"id\": \"full-test-trigger-1\",\n" +
                "        \"enabled\": true,\n" +
                "        \"name\": \"NumericData-01-low\",\n" +
                "        \"description\": \"description 1\",\n" +
                "        \"severity\": \"HIGH\",\n" +
                "        \"actions\": [\n" +
                "          {\"actionPlugin\":\"notification\", \"actionId\":\"notification-to-admin\"}\n" +
                "        ],\n" +
                "        \"context\": {\n" +
                "          \"name1\":\"value1\"\n" +
                "        },\n" +
                "        \"tags\": {\n" +
                "          \"tname1\":\"tvalue1\",\n" +
                "          \"tname2\":\"tvalue2\"\n" +
                "        }\n" +
                "      },\n" +
                "      \"dampenings\":[\n" +
                "        {\n" +
                "          \"triggerMode\": \"FIRING\",\n" +
                "          \"type\": \"STRICT\",\n" +
                "          \"evalTrueSetting\": 2,\n" +
                "          \"evalTotalSetting\": 2\n" +
                "        }\n" +
                "      ],\n" +
                "      \"conditions\":[\n" +
                "        {\n" +
                "          \"triggerMode\": \"FIRING\",\n" +
                "          \"type\": \"threshold\",\n" +
                "          \"dataId\": \"NumericData-01\",\n" +
                "          \"operator\": \"LT\",\n" +
                "          \"threshold\": 10.0,\n" +
                "          \"context\": {\n" +
                "            \"description\": \"Response Time\",\n" +
                "            \"unit\": \"ms\"\n" +
                "          }\n" +
                "        }\n" +
                "      ]\n" +
                "    }";

        // remove if it exists
        resp = client.delete(path: "triggers/full-test-trigger-1")
        assert(200 == resp.status || 404 == resp.status)

        // create the test trigger
        resp = client.post(path: "triggers/trigger", body: jsonTrigger)
        assertEquals(200, resp.status)

        resp = client.get(path: "triggers/trigger/full-test-trigger-1");
        assertEquals(200, resp.status)
        assertEquals("NumericData-01-low", resp.data.trigger.name)
        assertEquals(testTenant, resp.data.trigger.tenantId)
        assertEquals(1, resp.data.dampenings.size())
        assertEquals(1, resp.data.conditions.size())

        resp = client.delete(path: "triggers/full-test-trigger-1")
        assertEquals(200, resp.status)

        resp = client.delete(path: "actions/" + actionPlugin + "/" + actionId)
        assertEquals(200, resp.status)
    }

    @Test
    void createFullTriggerWithManagedPlugin() {
        // CREATE the action definition
        String actionPlugin = "email"

        String jsonTrigger = "{\n" +
                "      \"trigger\":{\n" +
                "        \"id\": \"full-test-trigger-1\",\n" +
                "        \"enabled\": true,\n" +
                "        \"name\": \"NumericData-01-low\",\n" +
                "        \"description\": \"description 1\",\n" +
                "        \"severity\": \"HIGH\",\n" +
                "        \"actions\": [\n" +
                "          {\"actionPlugin\":\"email\"}\n" +
                "        ],\n" +
                "        \"context\": {\n" +
                "          \"name1\":\"value1\"\n" +
                "        },\n" +
                "        \"tags\": {\n" +
                "          \"tname1\":\"tvalue1\",\n" +
                "          \"tname2\":\"tvalue2\"\n" +
                "        }\n" +
                "      },\n" +
                "      \"dampenings\":[\n" +
                "        {\n" +
                "          \"triggerMode\": \"FIRING\",\n" +
                "          \"type\": \"STRICT\",\n" +
                "          \"evalTrueSetting\": 2,\n" +
                "          \"evalTotalSetting\": 2\n" +
                "        }\n" +
                "      ],\n" +
                "      \"conditions\":[\n" +
                "        {\n" +
                "          \"triggerMode\": \"FIRING\",\n" +
                "          \"type\": \"threshold\",\n" +
                "          \"dataId\": \"NumericData-01\",\n" +
                "          \"operator\": \"LT\",\n" +
                "          \"threshold\": 10.0,\n" +
                "          \"context\": {\n" +
                "            \"description\": \"Response Time\",\n" +
                "            \"unit\": \"ms\"\n" +
                "          }\n" +
                "        }\n" +
                "      ]\n" +
                "    }";

        // remove if it exists
        def resp = client.delete(path: "triggers/full-test-trigger-1")
        assert(200 == resp.status || 404 == resp.status)

        // create the test trigger
        resp = client.post(path: "triggers/trigger", body: jsonTrigger)
        assertEquals(200, resp.status)
        assertTrue(resp.data.trigger.actions[0].actionId.startsWith("_managed"))
        String actionId = resp.data.trigger.actions[0].actionId

        resp = client.get(path: "triggers/trigger/full-test-trigger-1");
        assertEquals(200, resp.status)
        assertEquals("NumericData-01-low", resp.data.trigger.name)
        assertEquals(testTenant, resp.data.trigger.tenantId)
        assertEquals(1, resp.data.dampenings.size())
        assertEquals(1, resp.data.conditions.size())

        resp = client.delete(path: "triggers/full-test-trigger-1")
        assertEquals(200, resp.status)

        resp = client.delete(path: "actions/" + actionPlugin + "/" + actionId)
        assertEquals(200, resp.status)
    }

    @Test
    void verifyManagedActionIdIdentical() {
        // CREATE the action definition
        String actionPlugin = "email"

        String jsonTrigger = "{\n" +
                "      \"trigger\":{\n" +
                "        \"id\": \"full-test-trigger-1\",\n" +
                "        \"enabled\": true,\n" +
                "        \"name\": \"NumericData-01-low\",\n" +
                "        \"description\": \"description 1\",\n" +
                "        \"severity\": \"HIGH\",\n" +
                "        \"actions\": [\n" +
                "          {\"actionPlugin\":\"email\"}\n" +
                "        ],\n" +
                "        \"context\": {\n" +
                "          \"name1\":\"value1\"\n" +
                "        },\n" +
                "        \"tags\": {\n" +
                "          \"tname1\":\"tvalue1\",\n" +
                "          \"tname2\":\"tvalue2\"\n" +
                "        }\n" +
                "      },\n" +
                "      \"dampenings\":[\n" +
                "        {\n" +
                "          \"triggerMode\": \"FIRING\",\n" +
                "          \"type\": \"STRICT\",\n" +
                "          \"evalTrueSetting\": 2,\n" +
                "          \"evalTotalSetting\": 2\n" +
                "        }\n" +
                "      ],\n" +
                "      \"conditions\":[\n" +
                "        {\n" +
                "          \"triggerMode\": \"FIRING\",\n" +
                "          \"type\": \"threshold\",\n" +
                "          \"dataId\": \"NumericData-01\",\n" +
                "          \"operator\": \"LT\",\n" +
                "          \"threshold\": 10.0,\n" +
                "          \"context\": {\n" +
                "            \"description\": \"Response Time\",\n" +
                "            \"unit\": \"ms\"\n" +
                "          }\n" +
                "        }\n" +
                "      ]\n" +
                "    }";

        // remove if it exists
        def resp = client.delete(path: "triggers/full-test-trigger-1")
        assert(200 == resp.status || 404 == resp.status)

        // create the test trigger
        resp = client.post(path: "triggers/trigger", body: jsonTrigger)
        assertEquals(200, resp.status)
        assertTrue(resp.data.trigger.actions[0].actionId.startsWith("_managed"))
        String actionId = resp.data.trigger.actions[0].actionId

        jsonTrigger = jsonTrigger.replaceFirst("full-test-trigger-1", "full-test-trigger-2");

        resp = client.post(path: "triggers/trigger", body: jsonTrigger)
        assertEquals(200, resp.status)
        assertTrue(resp.data.trigger.actions[0].actionId.startsWith("_managed"))
        String actionId2 = resp.data.trigger.actions[0].actionId

        assertEquals(actionId, actionId2)

        def fullTrigger = resp.data
        fullTrigger.trigger.actions[0].actionId = null
        // Update and verify equality
        resp = client.put(path: "triggers/trigger/full-test-trigger-1", body: fullTrigger)
        assertEquals(200, resp.status)
        assertTrue(resp.data.trigger.actions[0].actionId.startsWith("_managed"))
        assertEquals(actionId, resp.data.trigger.actions[0].actionId)

        resp = client.delete(path: "triggers/full-test-trigger-1")
        assertEquals(200, resp.status)

        resp = client.delete(path: "triggers/full-test-trigger-2")
        assertEquals(200, resp.status)

        resp = client.delete(path: "actions/" + actionPlugin + "/" + actionId)
        assertEquals(200, resp.status)
    }

    @Test
    void createFullTriggerWithNullValues() {
        String jsonTrigger = "{\n" +
                "      \"trigger\":{\n" +
                "        \"id\": \"full-test-trigger-with-nulls\",\n" +
                "        \"enabled\": true,\n" +
                "        \"name\": \"NumericData-01-low\",\n" +
                "        \"description\": \"description 1\",\n" +
                "        \"severity\": null\n," +
                "        \"autoResolve\": null\n," +
                "        \"autoResolveAlerts\": null\n," +
                "        \"eventType\": null\n," +
                "        \"tenantId\": null\n," +
                "        \"description\": null\n," +
                "        \"autoEnable\": null\n," +
                "        \"autoDisable\": null\n" +
                "      }\n" +
                "    }";

        // remove if it exists
        def resp = client.delete(path: "triggers/full-test-trigger-with-nulls")
        assert(200 == resp.status || 404 == resp.status)

        // create the test trigger
        resp = client.post(path: "triggers/trigger", body: jsonTrigger)
        assertEquals(200, resp.status)

        resp = client.delete(path: "triggers/full-test-trigger-with-nulls")
        assertEquals(200, resp.status)
    }

    @Test
    void createFullTriggerWithNullId() {
        String jsonTrigger = "{\n" +
                "      \"trigger\":{\n" +
                "        \"enabled\": true,\n" +
                "        \"name\": \"NumericData-01-low\",\n" +
                "        \"description\": \"description 1\",\n" +
                "        \"severity\": null\n," +
                "        \"autoResolve\": null\n," +
                "        \"autoResolveAlerts\": null\n," +
                "        \"eventType\": null\n," +
                "        \"tenantId\": null\n," +
                "        \"description\": null\n," +
                "        \"autoEnable\": null\n," +
                "        \"autoDisable\": null\n" +
                "      }\n" +
                "    }";

        // The server should assign an id for it

        // create the test trigger
        def resp = client.post(path: "triggers/trigger", body: jsonTrigger)
        assertEquals(200, resp.status)

        // Verify is an UUID
        UUID.fromString(resp.data.trigger.id)

        resp = client.delete(path: "triggers/" + resp.data.trigger.id)
        assertEquals(200, resp.status)
    }

    @Test
    void updateFullTrigger() {
        // CREATE the action definition
        String actionPlugin = "notification"
        String actionId = "notification-to-admin";

        Map<String, String> actionProperties = new HashMap<>();
        actionProperties.put("endpoint_id", "1");

        ActionDefinition actionDefinition = new ActionDefinition(null, actionPlugin, actionId, actionProperties);

        def resp = client.post(path: "actions", body: actionDefinition)
        assert(200 == resp.status || 400 == resp.status)

        String jsonTrigger = "{\n" +
                "      \"trigger\":{\n" +
                "        \"id\": \"full-test-trigger-1\",\n" +
                "        \"enabled\": true,\n" +
                "        \"name\": \"NumericData-01-low\",\n" +
                "        \"description\": \"description 1\",\n" +
                "        \"severity\": \"HIGH\",\n" +
                "        \"actions\": [\n" +
                "          {\"actionPlugin\":\"notification\", \"actionId\":\"notification-to-admin\"}\n" +
                "        ],\n" +
                "        \"context\": {\n" +
                "          \"name1\":\"value1\"\n" +
                "        },\n" +
                "        \"tags\": {\n" +
                "          \"tname1\":\"tvalue1\",\n" +
                "          \"tname2\":\"tvalue2\"\n" +
                "        }\n" +
                "      },\n" +
                "      \"dampenings\":[\n" +
                "        {\n" +
                "          \"triggerMode\": \"FIRING\",\n" +
                "          \"type\": \"STRICT\",\n" +
                "          \"evalTrueSetting\": 2,\n" +
                "          \"evalTotalSetting\": 2\n" +
                "        }\n" +
                "      ],\n" +
                "      \"conditions\":[\n" +
                "        {\n" +
                "          \"triggerMode\": \"FIRING\",\n" +
                "          \"type\": \"threshold\",\n" +
                "          \"dataId\": \"NumericData-01\",\n" +
                "          \"operator\": \"LT\",\n" +
                "          \"threshold\": 10.0,\n" +
                "          \"context\": {\n" +
                "            \"description\": \"Response Time\",\n" +
                "            \"unit\": \"ms\"\n" +
                "          }\n" +
                "        }\n" +
                "      ]\n" +
                "    }";

        // remove if it exists
        resp = client.delete(path: "triggers/full-test-trigger-1")
        assert(200 == resp.status || 404 == resp.status)

        // create the test trigger
        resp = client.post(path: "triggers/trigger", body: jsonTrigger)
        assertEquals(200, resp.status)

        // get the test trigger
        resp = client.get(path: "triggers/trigger/full-test-trigger-1");
        assertEquals(200, resp.status)
        assertEquals("NumericData-01-low", resp.data.trigger.name)
        assertEquals("description 1", resp.data.trigger.description)
        assertEquals(true, resp.data.trigger.enabled)
        assertEquals(testTenant, resp.data.trigger.tenantId)
        assertEquals(1, resp.data.dampenings.size())
        assertEquals(1, resp.data.conditions.size())

        // try to update with null trigger
        def fullTrigger = resp.data;
        def trigger = fullTrigger.trigger;
        fullTrigger.trigger = null;
        resp = client.put(path: "triggers/trigger/full-test-trigger-1", body: fullTrigger)
        assertEquals(400, resp.status)
        fullTrigger.trigger = trigger;

        // try to update trigger type
        trigger.type = 'GROUP';
        resp = client.put(path: "triggers/trigger/full-test-trigger-1", body: fullTrigger)
        assertEquals(400, resp.status)
        trigger.type = 'STANDARD';

        // try an update with no changes (should succeed but not actually perform any updates, need to manually verify)
        resp = client.put(path: "triggers/trigger/full-test-trigger-1", body: fullTrigger)
        assertEquals(200, resp.status)

        // Try to update with null actionId
        def prevActionId = trigger.actions.get(0).actionId
        trigger.actions.get(0).actionId = null
        resp = client.put(path: "triggers/trigger/full-test-trigger-1", body: fullTrigger)
        assertEquals(200, resp.status)
        trigger.actions.get(0).actionId = prevActionId

        // re-get the test trigger
        def prevFullTrigger = fullTrigger;
        resp = client.get(path: "triggers/trigger/full-test-trigger-1");
        assertEquals(200, resp.status)
        fullTrigger = resp.data;
        // actionId has changed to a managed one since we didn't enter any
        assertNotEquals(prevActionId, fullTrigger.trigger.actions.get(0).actionId)
        assertTrue(fullTrigger.trigger.actions[0].actionId.startsWith("_managed"))

        // Set to original id
        trigger.actions.get(0).actionId = prevActionId

        // try an update with changes
        trigger = fullTrigger.trigger;
        trigger.description = "updated description";
        trigger.enabled = false;
        resp = client.put(path: "triggers/trigger/full-test-trigger-1", body: fullTrigger)
        assertEquals(200, resp.status)

        // re-get the test trigger
        resp = client.get(path: "triggers/trigger/full-test-trigger-1");
        assertEquals(200, resp.status)
        fullTrigger = resp.data;
        trigger = fullTrigger.trigger;
        assertEquals("updated description", trigger.description)
        assertEquals(false, trigger.enabled)

        // try an update that removes the dampening and condition set
        fullTrigger.dampenings = null;
        fullTrigger.conditions = null;
        resp = client.put(path: "triggers/trigger/full-test-trigger-1", body: fullTrigger)
        assertEquals(200, resp.status)

        // re-get the test trigger
        resp = client.get(path: "triggers/trigger/full-test-trigger-1");
        assertEquals(200, resp.status)
        fullTrigger = resp.data;
        assertEquals(null, fullTrigger.dampenings);
        assertEquals(null, fullTrigger.conditions);

        // add back dampening
        fullTrigger.dampenings = "[\n" +
                "        {\n" +
                "          \"triggerMode\": \"FIRING\",\n" +
                "          \"type\": \"STRICT\",\n" +
                "          \"evalTrueSetting\": 2,\n" +
                "          \"evalTotalSetting\": 2\n" +
                "        }]";
        resp = client.put(path: "triggers/trigger/full-test-trigger-1", body: fullTrigger)
        assertEquals(200, resp.status)

        // re-get the test trigger
        resp = client.get(path: "triggers/trigger/full-test-trigger-1");
        assertEquals(200, resp.status)
        fullTrigger = resp.data;
        assertTrue(null != fullTrigger.dampenings);
        assertEquals(1, fullTrigger.dampenings.size());
        assertEquals("STRICT", fullTrigger.dampenings[0].type);

        // add back condition
        fullTrigger.conditions = "[\n" +
                "        {\n" +
                "          \"triggerMode\": \"FIRING\",\n" +
                "          \"type\": \"threshold\",\n" +
                "          \"dataId\": \"NumericData-01\",\n" +
                "          \"operator\": \"LT\",\n" +
                "          \"threshold\": 10.0,\n" +
                "          \"context\": {\n" +
                "            \"description\": \"Response Time\",\n" +
                "            \"unit\": \"ms\"\n" +
                "          }\n" +
                "        }]";
        resp = client.put(path: "triggers/trigger/full-test-trigger-1", body: fullTrigger)
        assertEquals(200, resp.status)

        // re-get the test trigger
        resp = client.get(path: "triggers/trigger/full-test-trigger-1");
        assertEquals(200, resp.status)
        fullTrigger = resp.data;
        assertTrue(null != fullTrigger.conditions);
        assertEquals(1, fullTrigger.conditions.size());
        assertEquals("THRESHOLD", fullTrigger.conditions[0].type);

        // Update conditios' part only
        fullTrigger.conditions[0].dataId = "somethingElse"
        resp = client.put(path: "triggers/trigger/full-test-trigger-1", body: fullTrigger)
        assertEquals(200, resp.status)
        assertEquals(fullTrigger.conditions[0].dataId, resp.data.conditions[0].dataId)

        // remove the test trigger
        resp = client.delete(path: "triggers/full-test-trigger-1")
        assertEquals(200, resp.status)

        resp = client.delete(path: "actions/" + actionPlugin + "/" + actionId)
        assertEquals(200, resp.status)
    }

    @Test
    void failWithUnknownActionOrPluginFullTrigger() {
        String jsonTrigger = "{\n" +
                "      \"trigger\":{\n" +
                "        \"id\": \"full-test-trigger-1\",\n" +
                "        \"enabled\": true,\n" +
                "        \"name\": \"NumericData-01-low\",\n" +
                "        \"description\": \"description 1\",\n" +
                "        \"severity\": \"HIGH\",\n" +
                "        \"actions\": [\n" +
                // Unknown email-to-nothing action
                "          {\"actionPlugin\":\"notification\", \"actionId\":\"webhook-to-nothing\"}\n" +
                "        ],\n" +
                "        \"context\": {\n" +
                "          \"name1\":\"value1\"\n" +
                "        },\n" +
                "        \"tags\": {\n" +
                "          \"tname1\":\"tvalue1\",\n" +
                "          \"tname2\":\"tvalue2\"\n" +
                "        }\n" +
                "      },\n" +
                "      \"dampenings\":[\n" +
                "        {\n" +
                "          \"triggerMode\": \"FIRING\",\n" +
                "          \"type\": \"STRICT\",\n" +
                "          \"evalTrueSetting\": 2,\n" +
                "          \"evalTotalSetting\": 2\n" +
                "        }\n" +
                "      ],\n" +
                "      \"conditions\":[\n" +
                "        {\n" +
                "          \"triggerMode\": \"FIRING\",\n" +
                "          \"type\": \"threshold\",\n" +
                "          \"dataId\": \"NumericData-01\",\n" +
                "          \"operator\": \"LT\",\n" +
                "          \"threshold\": 10.0,\n" +
                "          \"context\": {\n" +
                "            \"description\": \"Response Time\",\n" +
                "            \"unit\": \"ms\"\n" +
                "          }\n" +
                "        }\n" +
                "      ]\n" +
                "    }";

        // remove if it exists
        def resp = client.delete(path: "triggers/full-test-trigger-1")
        assert(200 == resp.status || 404 == resp.status)

        // create the test trigger
        resp = client.post(path: "triggers/trigger", body: jsonTrigger)
        assertEquals(400, resp.status)

        jsonTrigger = "{\n" +
                "      \"trigger\":{\n" +
                "        \"id\": \"full-test-trigger-1\",\n" +
                "        \"enabled\": true,\n" +
                "        \"name\": \"NumericData-01-low\",\n" +
                "        \"description\": \"description 1\",\n" +
                "        \"severity\": \"HIGH\",\n" +
                "        \"actions\": [\n" +
                // Unknown plugin
                "          {\"actionPlugin\":\"unknown\", \"actionId\":\"email-to-nothing\"}\n" +
                "        ],\n" +
                "        \"context\": {\n" +
                "          \"name1\":\"value1\"\n" +
                "        },\n" +
                "        \"tags\": {\n" +
                "          \"tname1\":\"tvalue1\",\n" +
                "          \"tname2\":\"tvalue2\"\n" +
                "        }\n" +
                "      },\n" +
                "      \"dampenings\":[\n" +
                "        {\n" +
                "          \"triggerMode\": \"FIRING\",\n" +
                "          \"type\": \"STRICT\",\n" +
                "          \"evalTrueSetting\": 2,\n" +
                "          \"evalTotalSetting\": 2\n" +
                "        }\n" +
                "      ],\n" +
                "      \"conditions\":[\n" +
                "        {\n" +
                "          \"triggerMode\": \"FIRING\",\n" +
                "          \"type\": \"threshold\",\n" +
                "          \"dataId\": \"NumericData-01\",\n" +
                "          \"operator\": \"LT\",\n" +
                "          \"threshold\": 10.0,\n" +
                "          \"context\": {\n" +
                "            \"description\": \"Response Time\",\n" +
                "            \"unit\": \"ms\"\n" +
                "          }\n" +
                "        }\n" +
                "      ]\n" +
                "    }";

        // create the test trigger
        resp = client.post(path: "triggers/trigger", body: jsonTrigger)
        assertEquals(400, resp.status)
    }

    @Test
    void validateMultipleTriggerModeConditions() {
        Trigger testTrigger = new Trigger("test-multiple-mode-conditions", "No-Metric")

        // make sure clean test trigger exists
        client.delete(path: "triggers/test-multiple-mode-conditions")
        def resp = client.post(path: "triggers", body: testTrigger)
        assertEquals(200, resp.status)

        ThresholdCondition testCond1 = new ThresholdCondition("test-multiple-mode-conditions", Mode.FIRING,
                "No-Metric", ThresholdCondition.Operator.GT, 10.12);

        ThresholdCondition testCond2 = new ThresholdCondition("test-multiple-mode-conditions", Mode.AUTORESOLVE,
                "No-Metric", ThresholdCondition.Operator.LT, 4.10);

        Collection<Condition> conditions = new ArrayList<>(2);
        conditions.add( testCond1 );
        conditions.add( testCond2 );
        resp = client.put(path: "triggers/test-multiple-mode-conditions/conditions/firing", body: conditions)
        assertEquals(400, resp.status)

        resp = client.delete(path: "triggers/test-multiple-mode-conditions")
        assertEquals(200, resp.status)
    }

    @Test
    void validateMultipleTriggerModeConditionsInGroups() {
        Trigger groupTrigger = new Trigger("group-trigger", "group-trigger");
        groupTrigger.setEnabled(false);

        // remove if it exists
        def resp = client.delete(path: "triggers/groups/group-trigger", query: [keepNonOrphans:false,keepOrphans:false])
        assert(200 == resp.status || 404 == resp.status)

        // create the group
        resp = client.post(path: "triggers/groups", body: groupTrigger)
        assertEquals(200, resp.status)

        resp = client.get(path: "triggers/group-trigger")
        assertEquals(200, resp.status)
        def jsonOut = JsonOutput.toJson(resp.data)
        groupTrigger = fromJson(jsonOut, Trigger.class)
        assertEquals( true, groupTrigger.isGroup() );

        ThresholdCondition cond1 = new ThresholdCondition("group-trigger", Mode.FIRING, "DataId1-Token",
                ThresholdCondition.Operator.GT, 10.0);
        ThresholdCondition cond2 = new ThresholdCondition("group-trigger", Mode.AUTORESOLVE, "DataId2-Token",
                ThresholdCondition.Operator.LT, 20.0);

        Map<String, Map<String, String>> dataIdMemberMap = new HashMap<>();
        GroupConditionsInfo groupConditionsInfo = new GroupConditionsInfo(Arrays.asList(cond1, cond2), dataIdMemberMap)

        resp = client.put(path: "triggers/groups/group-trigger/conditions/firing", body: groupConditionsInfo)
        assertEquals(400, resp.status)

        // remove group trigger
        resp = client.delete(path: "triggers/groups/group-trigger")
        assertEquals(200, resp.status)
    }

    @Test
    void updateOnlyExpression() {
        def jsonTrigger = """{
            "trigger": {
                "id": "detect-floating-4-tutorial2",
                "name": "Node with no infra second",
                "description": "These hosts are not allocated to any known infrastructure provider",
                "enabled": true,
                "eventType": "ALERT",
                "firingMatch": "ALL",
                "actions": [
                    {
                        "actionPlugin": "email"
                    }
                ]
            },
            "conditions": [
                {
                    "triggerMode": "FIRING",
                    "type": "EVENT",
                    "dataId": "platform.inventory.host-egress",
                    "expression": "facts.nomatch = 'false'"
                }
            ]
        }
        """

        // remove if it exists
        def resp = client.delete(path: "triggers/detect-floating-4-tutorial2")
        assert(200 == resp.status || 404 == resp.status)

        // create the test trigger
        resp = client.post(path: "triggers/trigger", body: jsonTrigger)
        assertEquals(200, resp.status)
        assertTrue(resp.data.trigger.actions[0].actionId.startsWith("_managed"))

        def fullTrigger = resp.data
        def updatedExpression = "facts.nomatch = 'true'"
        fullTrigger.conditions[0].expression = updatedExpression

        resp = client.put(path: "triggers/trigger/detect-floating-4-tutorial2", body: fullTrigger)
        assertEquals(200, resp.status)
        assertEquals(updatedExpression, resp.data.conditions[0].expression)

        resp = client.delete(path: "triggers/detect-floating-4-tutorial2")
        assertEquals(200, resp.status)
    }

    @Test
    void testSingleEnableDisable() {
        def jsonTrigger = """{
            "trigger": {
                "id": "detect-floating-4-tutorial2",
                "name": "Node with no infra second",
                "description": "These hosts are not allocated to any known infrastructure provider",
                "enabled": true,
                "eventType": "ALERT",
                "firingMatch": "ALL",
                "actions": [
                    {
                        "actionPlugin": "email"
                    }
                ]
            },
            "conditions": [
                {
                    "triggerMode": "FIRING",
                    "type": "EVENT",
                    "dataId": "platform.inventory.host-egress",
                    "expression": "facts.nomatch = 'false'"
                }
            ]
        }
        """
        // remove if it exists
        def resp = client.delete(path: "triggers/detect-floating-4-tutorial2")
        assert(200 == resp.status || 404 == resp.status)

        // create the test full trigger
        resp = client.post(path: "triggers/trigger", body: jsonTrigger)
        assertEquals(200, resp.status)
        assertEquals(true, resp.data.trigger.enabled)

        // Re-enable it, no error should happen
        resp = client.put(path: "triggers/detect-floating-4-tutorial2/enable")
        assertEquals(200, resp.status)

        resp = client.get(path: "triggers/detect-floating-4-tutorial2")
        assertEquals(200, resp.status)
        assertEquals(true, resp.data.enabled)

        // Disable it
        resp = client.delete(path: "triggers/detect-floating-4-tutorial2/enable")
        assertEquals(200, resp.status)

        resp = client.get(path: "triggers/detect-floating-4-tutorial2")
        assertEquals(200, resp.status)
        assertEquals(false, resp.data.enabled)

        // Again disable the disabled
        resp = client.delete(path: "triggers/detect-floating-4-tutorial2/enable")
        assertEquals(200, resp.status)

        resp = client.get(path: "triggers/detect-floating-4-tutorial2")
        assertEquals(200, resp.status)
        assertEquals(false, resp.data.enabled)

        // Enable it
        resp = client.put(path: "triggers/detect-floating-4-tutorial2/enable")
        assertEquals(200, resp.status)

        resp = client.get(path: "triggers/detect-floating-4-tutorial2")
        assertEquals(200, resp.status)
        assertEquals(true, resp.data.enabled)
    }
}
