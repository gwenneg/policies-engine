package org.hawkular.alerts.api.model.trigger;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.hawkular.alerts.api.doc.DocModel;
import org.hawkular.alerts.api.doc.DocModelProperty;
import org.hawkular.alerts.api.model.action.TimeConstraint;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Links an ActionDefinition with a Trigger.
 *
 * It can add optional constraints that determine when an action will be executed:
 *
 * - A set of Alert.Status (represented by its string value).
 *   The action will be executed if the Alert which is linked is on one of the states defined.
 *   Unlike Alerts, Events don't have lifecycle, TriggerActions on Events are all executed at Event creation time.
 *
 * - A TimeConstraint object that defines a time interval in absolute or relative way.
 *   The action will be executed if the action creation time is satisfied by the time interval.
 *
 * @author Jay Shaughnessy
 * @author Lucas Ponce
 */
@DocModel(description = "Links an <<ActionDefinition>> with a <<Trigger>>.\n" +
        "\n" +
        "The TriggerAction can override the constraints set on the <<ActionDefintion>>.\n" +
        "If a <<TriggerAction>> defines any constraints the <<ActionDefinition>> constraints will be ignored.\n" +
        "If a <<TriggerAction>> defines no constraints the <<ActionDefinition>> constraints will be used.\n" +
        "If a <<TriggerAction>> defines properties and no actionId, the <<ActionDefinition>> will be created.\n")
public class TriggerAction implements Serializable {

    private static final long serialVersionUID = 1L;

    @DocModelProperty(description = "Tenant id owner of this trigger.",
            position = 0,
            allowableValues = "Tenant is overwritten from Hawkular-Tenant HTTP header parameter request")
    @JsonInclude(Include.NON_NULL)
    private String tenantId;

    @DocModelProperty(description = "Action plugin identifier.",
            position = 1,
            required = true,
            allowableValues = "Only plugins deployed on the system are valid.")
    @JsonInclude
    private String actionPlugin;

    @DocModelProperty(description = "Action definition identifier.",
            position = 2,
            allowableValues = "Only existing action definitions on the system are valid.")
    @JsonInclude
    private String actionId;

    @DocModelProperty(description = "Plugin properties. Each plugin defines its own specific properties that can be " +
            "supplied at action definition level. If properties is given, actionId must be empty as it is generated.",
            position = 3)
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> properties;

    @DocModelProperty(description = "A list of Alert.Status restricting active states for this action.",
            position = 4,
            allowableValues = "OPEN, ACKNOWLEDGED, RESOLVED")
    @JsonInclude(Include.NON_EMPTY)
    private Set<String> states;

    @DocModelProperty(description = "A TimeConstraint restricting active times for this action.",
            position = 5)
    @JsonInclude(Include.NON_NULL)
    private TimeConstraint calendar;

    public TriggerAction() {
        this(null, null);
    }

    public TriggerAction(String actionPlugin, String actionId) {
        this(null, actionPlugin, actionId);
    }

    public TriggerAction(String tenantId, String actionPlugin, String actionId) {
        this(tenantId, actionPlugin, actionId, new HashSet<>(), null, null);
    }

    public TriggerAction(String tenantId, String actionPlugin, Map<String, String> properties) {
        this(tenantId, actionPlugin, null, new HashSet<>(), null, properties);
    }

    public TriggerAction(String tenantId, String actionPlugin, String actionId, Set<String> states) {
        this(tenantId, actionPlugin, actionId, new HashSet<>(states), null, null);
    }

    public TriggerAction(String tenantId, String actionPlugin, String actionId, TimeConstraint calendar) {
        this(tenantId, actionPlugin, actionId, new HashSet<>(), calendar, null);
    }

    public TriggerAction(TriggerAction triggerAction) {
        if (triggerAction == null) {
            throw new IllegalArgumentException("triggerAction must be not null");
        }
        this.tenantId = triggerAction.getTenantId();
        this.actionPlugin = triggerAction.getActionPlugin();
        this.actionId = triggerAction.getActionId();
        this.states = new HashSet<>(triggerAction.getStates());
        this.calendar = triggerAction.getCalendar() != null ? new TimeConstraint(triggerAction.getCalendar()) : null;
    }

    public TriggerAction(String tenantId, String actionPlugin, String actionId, Set<String> states,
                         TimeConstraint calendar, Map<String, String> properties) {
        this.tenantId = tenantId;
        this.actionPlugin = actionPlugin;
        this.actionId = actionId;
        this.states = states;
        this.calendar = calendar;
        this.properties = properties;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getActionPlugin() {
        return actionPlugin;
    }

    public void setActionPlugin(String actionPlugin) {
        this.actionPlugin = actionPlugin;
    }

    public String getActionId() {
        return actionId;
    }

    public void setActionId(String actionId) {
        this.actionId = actionId;
    }

    public Set<String> getStates() {
        if (states == null) {
            states = new HashSet<>();
        }
        return states;
    }

    public void setStates(Set<String> states) {
        this.states = states;
    }

    public void addState(String state) {
        getStates().add(state);
    }

    public TimeConstraint getCalendar() {
        return calendar;
    }

    public void setCalendar(TimeConstraint calendar) {
        this.calendar = calendar;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TriggerAction that = (TriggerAction) o;

        if (tenantId != null ? !tenantId.equals(that.tenantId) : that.tenantId != null) return false;
        if (actionPlugin != null ? !actionPlugin.equals(that.actionPlugin) : that.actionPlugin != null) return false;
        if (actionId != null ? !actionId.equals(that.actionId) : that.actionId != null) return false;
        if (states != null ? !states.equals(that.states) : that.states != null) return false;
        return calendar != null ? calendar.equals(that.calendar) : that.calendar == null;

    }

    @Override
    public int hashCode() {
        int result = tenantId != null ? tenantId.hashCode() : 0;
        result = 31 * result + (actionPlugin != null ? actionPlugin.hashCode() : 0);
        result = 31 * result + (actionId != null ? actionId.hashCode() : 0);
        result = 31 * result + (states != null ? states.hashCode() : 0);
        result = 31 * result + (calendar != null ? calendar.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "TriggerAction" + '[' +
                "tenantId='" + tenantId + '\'' +
                ", actionPlugin='" + actionPlugin + '\'' +
                ", actionId='" + actionId + '\'' +
                ", states=" + states +
                ", calendar='" + calendar + '\'' +
                ']';
    }
}
