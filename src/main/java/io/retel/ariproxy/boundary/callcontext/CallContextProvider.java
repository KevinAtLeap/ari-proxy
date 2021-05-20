package io.retel.ariproxy.boundary.callcontext;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.japi.pf.ReceiveBuilder;
import io.retel.ariproxy.akkajavainterop.PatternsAdapter;
import io.retel.ariproxy.boundary.callcontext.api.CallContextLookupError;
import io.retel.ariproxy.boundary.callcontext.api.CallContextProvided;
import io.retel.ariproxy.boundary.callcontext.api.CallContextRegistered;
import io.retel.ariproxy.boundary.callcontext.api.ProvideCallContext;
import io.retel.ariproxy.boundary.callcontext.api.ProviderPolicy;
import io.retel.ariproxy.boundary.callcontext.api.RegisterCallContext;
import io.retel.ariproxy.health.api.ProvideHealthReport;
import io.retel.ariproxy.health.api.ProvideMonitoring;
import io.retel.ariproxy.persistence.PersistentCache;
import io.vavr.concurrent.Future;
import java.util.UUID;

public class CallContextProvider extends PersistentCache {

  public static final String ACTOR_NAME = "call-context-provider";

  public static Props props(final ActorRef metricsService) {
    return Props.create(CallContextProvider.class, metricsService);
  }

  private CallContextProvider(final ActorRef metricsService) {
    super(metricsService);
  }

  @Override
  protected String keyPrefix() {
    return "ari-proxy:call-context-provider";
  }

  @Override
  public void preStart() throws Exception {
    getContext().getSystem().eventStream().publish(new ProvideMonitoring(ACTOR_NAME, self()));
    super.preStart();
  }

  public Receive createReceive() {
    return ReceiveBuilder.create()
        .match(RegisterCallContext.class, this::registerCallContextHandler)
        .match(ProvideCallContext.class, this::provideCallContextHandler)
        .match(ProvideHealthReport.class, this::provideHealthReportHandler)
        .build();
  }

  private void registerCallContextHandler(RegisterCallContext cmd) {
    log().debug("Got command: {}", cmd);

    final String resourceId = cmd.resourceId();
    final String callContext = cmd.callContext();

    final ActorRef sender = sender();

    update(resourceId, callContext)
        .andThen(
            setDone -> {
              log()
                  .debug("Registered resourceId '{}' => callContext '{}'", resourceId, callContext);
              sender.tell(new CallContextRegistered(resourceId, callContext), self());
            });
  }

  private void provideCallContextHandler(final ProvideCallContext cmd) {
    log().debug("Got command: {}", cmd);

    final ActorRef sender = sender();

    final Future<CallContextProvided> callContext =
            ProviderPolicy.CREATE_IF_MISSING.equals(cmd.policy()) ?
                    provideCallContextForCreateIfMissingPolicy(cmd) : provideCallContextForLookupOnlyPolicy(cmd);

    PatternsAdapter.pipeTo(callContext, sender, context().dispatcher());
  }

  private Future<CallContextProvided> provideCallContextForLookupOnlyPolicy(final ProvideCallContext cmd) {
    return query(cmd.resourceId())
        .flatMap(
            maybeCallContextFromDB ->
                maybeCallContextFromDB
                    .map(
                        callContextFromDB ->
                            Future.successful(new CallContextProvided(callContextFromDB)))
                    .getOrElse(
                        () -> Future.failed(
                            new CallContextLookupError(
                                String.format(
                                    "Failed to lookup call context for resource id %s...",
                                    cmd.resourceId())))))
        .await();
  }

  private Future<CallContextProvided> provideCallContextForCreateIfMissingPolicy(final ProvideCallContext cmd) {
    if(cmd.maybeCallContextFromChannelVars().isDefined()){
      final CallContextProvided callContextFromChannelVars = new CallContextProvided(cmd.maybeCallContextFromChannelVars().get());
      update(cmd.resourceId(), callContextFromChannelVars.callContext())
              .map(setDone -> new CallContextProvided(setDone.getValue()));

      return Future.successful(callContextFromChannelVars);
    }

    final Future<CallContextProvided> callContext = query(cmd.resourceId()).map(
            maybeCallContextFromDB ->
                    new CallContextProvided(maybeCallContextFromDB.getOrElse(() -> {
                      final String generatedCallContext = UUID.randomUUID().toString();
                      update(cmd.resourceId(), generatedCallContext)
                              .map(setDone -> new CallContextProvided(setDone.getValue()));
                      return generatedCallContext;
                    })
    ));

    return callContext;
  }

  private void provideHealthReportHandler(ProvideHealthReport cmd) {
    PatternsAdapter.pipeTo(provideHealthReport(), sender(), context().dispatcher());
  }
}
