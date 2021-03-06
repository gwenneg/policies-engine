package com.redhat.cloud.policies.engine.handlers;

import com.redhat.cloud.policies.engine.handlers.util.ResponseUtil;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import com.redhat.cloud.policies.engine.actions.QuarkusActionPluginRegister;
import org.hawkular.alerts.api.doc.*;
import org.hawkular.alerts.api.services.DefinitionsService;
import org.hawkular.alerts.log.MsgLogger;
import org.hawkular.alerts.log.MsgLogging;

import javax.annotation.PostConstruct;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.Collection;
import java.util.Set;

import static org.hawkular.alerts.api.doc.DocConstants.GET;

/**
 * @author Jay Shaughnessy
 * @author Lucas Ponce
 */
@DocEndpoint(value = "/plugins", description = "Query operations for action plugins")
public class ActionPluginHandler {
    private static final MsgLogger log = MsgLogging.getMsgLogger(ActionPluginHandler.class);

    @Inject
    DefinitionsService definitionsService;

    @Inject
    QuarkusActionPluginRegister pluginRegister;

    @PostConstruct
    public void init(@Observes Router router) {
        String path = "/hawkular/alerts/plugins";
        router.get(path).handler(this::findActionPlugins);
        router.get(path + "/:actionPlugin").handler(this::getActionPlugin);
    }

    @DocPath(method = GET,
            path = "/",
            name = "Find all action plugins.")
    @DocResponses(value = {
            @DocResponse(code = 200, message = "Successfully fetched list of actions plugins.", response = String.class, responseContainer = "List"),
            @DocResponse(code = 500, message = "Internal server error.", response = ResponseUtil.ApiError.class)
    })
    public void findActionPlugins(RoutingContext routing) {
        routing.vertx()
                .executeBlocking(future -> {
                   ResponseUtil.checkTenant(routing);
                   try {
                       Collection<String> actionPlugins = definitionsService.getActionPlugins();
                       log.debugf("ActionPlugins: %s", actionPlugins);
                        future.complete(actionPlugins);
                   } catch (Exception e) {
                       log.errorf("Error querying all plugins. Reason: %s", e.toString());
                       throw new ResponseUtil.InternalServerException(e.toString());
                   }
                }, res -> ResponseUtil.result(routing, res));
    }

    @DocPath(method = GET,
            path = "/{actionPlugin}",
            name = "Find list of properties to fill for a specific action plugin.",
            notes = "Each action plugin can have a different and variable number of properties. + \n" +
                    "This method should be invoked before of a creation of a new action.")
    @DocParameters(value = {
            @DocParameter(name = "actionPlugin", required = true, path = true,
                    description = "Action plugin to query.")
    })
    @DocResponses(value = {
            @DocResponse(code = 200, message = "Success, Action Plugin found.", response = String.class, responseContainer = "List"),
            @DocResponse(code = 404, message = "Action Plugin not found.", response = ResponseUtil.ApiError.class),
            @DocResponse(code = 500, message = "Internal server error.", response = ResponseUtil.ApiError.class)
    })
    public void getActionPlugin(RoutingContext routing) {
        String actionPlugin = routing.request().getParam("actionPlugin");
        routing.vertx()
                .executeBlocking(future -> {
                    ResponseUtil.checkTenant(routing);
                    Set<String> actionPluginProps;
                    try {
                        actionPluginProps = definitionsService.getActionPlugin(actionPlugin);
                        log.debugf("ActionPlugin: %s - Properties: %s", actionPlugin, actionPluginProps);
                        if (actionPluginProps == null) {
                            future.fail(new ResponseUtil.NotFoundException("Not found action plugin: " + actionPlugin));
                        } else {
                            future.complete(actionPluginProps);
                        }
                    } catch (Exception e) {
                        log.errorf("Error querying plugin %s. Reason: %s", actionPlugin, e.toString());
                        throw new ResponseUtil.InternalServerException(e.toString());
                    }
                }, res -> ResponseUtil.result(routing, res));
    }
}
