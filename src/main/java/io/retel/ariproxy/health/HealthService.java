package io.retel.ariproxy.health;

import static akka.http.javadsl.server.Directives.complete;
import static akka.http.javadsl.server.Directives.get;
import static akka.http.javadsl.server.Directives.path;
import static akka.http.javadsl.server.PathMatchers.segment;

import akka.NotUsed;
import akka.actor.AbstractLoggingActor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.server.Directives;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.directives.RouteAdapter;
import akka.japi.pf.ReceiveBuilder;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.retel.ariproxy.akkajavainterop.PatternsAdapter;
import io.retel.ariproxy.health.api.*;
import io.vavr.Tuple2;
import io.vavr.collection.HashMap;
import io.vavr.concurrent.Future;
import io.vavr.control.Try;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

public class HealthService extends AbstractLoggingActor {

  public static final String ACTOR_NAME = "HealthService";

  private static final ObjectWriter writer = new ObjectMapper().writer();
  private static final long TIMEOUT_MILLIS = 100;
  private static final String OK_MESSAGE = "feeling good";

  private final int httpPort;
  private CompletionStage<ServerBinding> binding;
  private HashMap<ActorRef, String> subscriptions = HashMap.empty();

  public static Props props(final int httpPort) {
    return Props.create(HealthService.class, httpPort);
  }

  private HealthService(final int httpPort) {
    this.httpPort = httpPort;
  }

  @Override
  public Receive createReceive() {
    return ReceiveBuilder.create()
        .match(ProvideMonitoring.class, this::handleProvideMonitoring)
        .matchAny(a -> log().warning("received unknown message {}", a))
        .build();
  }

  private void handleProvideMonitoring(ProvideMonitoring provideMonitoring) {
    subscriptions =
        subscriptions.put(
            provideMonitoring.getSubscriberRef(), provideMonitoring.getSubscriberName());
  }

  @Override
  public void preStart() throws Exception {
    context().system().eventStream().subscribe(self(), ProvideMonitoring.class);
    this.binding = startHttpServer();

    context().system().eventStream().publish(NewHealthRecipient.getInstance());

    Future.fromCompletableFuture(binding.toCompletableFuture()).onFailure(t -> System.exit(-1));

    super.preStart();
  }

  @Override
  public void postStop() throws Exception {
    context().system().eventStream().unsubscribe(self(), ProvideMonitoring.class);
    binding.thenCompose(ServerBinding::unbind);
    super.postStop();
  }

  private CompletionStage<ServerBinding> startHttpServer() {
    final ActorSystem system = getContext().getSystem();
    final Http http = Http.get(system);
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    final Flow<HttpRequest, HttpResponse, NotUsed> routeFlow =
        createRoute().flow(system, materializer);

    final String address = "0.0.0.0";
    final CompletionStage<ServerBinding> binding =
        http.bindAndHandle(routeFlow, ConnectHttp.toHost(address, httpPort), materializer);

    log().info("HTTP server online at http://{}:{}/...", address, httpPort);

    return binding;
  }

  private Route createRoute() {
    return Directives.route(
        path("health", () -> get(() -> handleHealthBaseRoute())),
        path(segment("health").slash("smoke"), () -> get(() -> handleHealthSmokeRoute())));
  }

  private RouteAdapter handleHealthSmokeRoute() {
    return complete(HttpEntities.create(OK_MESSAGE));
  }

  private RouteAdapter handleHealthBaseRoute() {
    final HealthReport report =
        subscriptions
            .toJavaParallelStream()
            .map(retrieveHealthReport)
            .reduce(HealthReport.empty(), (acc, current) -> acc.merge(current));

    return complete(
        HttpEntities.create(
            ContentTypes.APPLICATION_JSON,
            Try.of(
                    () ->
                        writer.writeValueAsString(
                            HealthResponse.fromErrors(report.errors().toJavaList())))
                .getOrElseThrow(t -> new RuntimeException(t))));
  }

  private static final Function<Tuple2<ActorRef, String>, HealthReport> retrieveHealthReport =
      subscription ->
          PatternsAdapter.<HealthReport>ask(
                  subscription._1, new ProvideHealthReport(), TIMEOUT_MILLIS)
              .await()
              .getOrElse(HealthReport.error("failed to get report for " + subscription._2));
}
