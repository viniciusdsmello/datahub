package com.linkedin.metadata.boot;

import com.linkedin.gms.factory.config.ConfigurationProvider;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;


/**
 * Responsible for coordinating starting steps that happen before the application starts up.
 */
@Slf4j
@Component
public class OnBootApplicationListener {
 private static final Set<Integer> ACCEPTED_HTTP_CODES = Set.of(HttpStatus.SC_OK, HttpStatus.SC_MOVED_PERMANENTLY,
         HttpStatus.SC_MOVED_TEMPORARILY, HttpStatus.SC_FORBIDDEN, HttpStatus.SC_UNAUTHORIZED);

  private static final String ROOT_WEB_APPLICATION_CONTEXT_ID = String.format("%s:", WebApplicationContext.class.getName());

  private final CloseableHttpClient httpClient = HttpClients.createDefault();

  private final ExecutorService executorService = Executors.newSingleThreadExecutor();

  @Autowired
  @Qualifier("bootstrapManager")
  private BootstrapManager _bootstrapManager;

  @Autowired
  @Qualifier("configurationProvider")
  private ConfigurationProvider provider;


  @EventListener(ContextRefreshedEvent.class)
  public void onApplicationEvent(@Nonnull ContextRefreshedEvent event) {
    log.warn("OnBootApplicationListener context refreshed! {} event: {}",
        ROOT_WEB_APPLICATION_CONTEXT_ID.equals(event.getApplicationContext().getId()), event);
    if (ROOT_WEB_APPLICATION_CONTEXT_ID.equals(event.getApplicationContext().getId())) {
      executorService.submit(isSchemaRegistryAPIServeletReady());
    }
  }

  public Runnable isSchemaRegistryAPIServeletReady() {
    return () -> {
        final HttpGet request = new HttpGet(provider.getKafka().getSchemaRegistry().getUrl());
        int timeouts = 30;
        boolean openAPIServeletReady = false;
        while (!openAPIServeletReady && timeouts > 0) {
          try {
            log.info("Sleeping for 1 second");
            Thread.sleep(1000);
            StatusLine statusLine = httpClient.execute(request).getStatusLine();
            if (ACCEPTED_HTTP_CODES.contains(statusLine.getStatusCode())) {
              log.info("Connected! Authentication not tested.");
              openAPIServeletReady = true;
            }
          } catch (IOException | InterruptedException e) {
            log.info("Failed to connect to open servlet: {}", e.getMessage());
          }
          timeouts--;
        }
        if (!openAPIServeletReady) {
          log.error("Failed to bootstrap DataHub, OpenAPI servlet was not ready after 30 seconds");
          System.exit(1);
        } else {
        _bootstrapManager.start();
        }
    };
  }
}
