package com.linkedin.metadata.config.search;

import com.linkedin.metadata.config.search.custom.CustomSearchConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;


@Data
@Slf4j
public class CustomConfiguration {
  private boolean enabled;
  private String file;

  /**
   * Materialize the search configuration from a location external to main application.yml
   * @param mapper yaml enabled jackson mapper
   * @return search configuration class
   * @throws IOException
   */
  public CustomSearchConfiguration resolve(ObjectMapper mapper) throws IOException {
    if (enabled) {
      log.info("Custom search configuration enabled.");
      try (InputStream stream = new ClassPathResource(file).getInputStream()) {
        log.info("Custom search configuration found in classpath: {}", file);
        return mapper.readValue(stream, CustomSearchConfiguration.class);
      } catch (FileNotFoundException e) {
        try (InputStream stream = new FileSystemResource(file).getInputStream()) {
          log.info("Custom search configuration found in filesystem: {}", file);
          return mapper.readValue(stream, CustomSearchConfiguration.class);
        }
      }
    } else {
      log.info("Custom search configuration disabled.");
      return null;
    }
  }
}
