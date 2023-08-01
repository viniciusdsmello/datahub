package com.linkedin.datahub.graphql.resolvers.tag;

import com.datahub.authentication.Authentication;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.generated.CreateTagInput;
import com.linkedin.datahub.graphql.resolvers.mutate.MutationUtils;
import com.linkedin.entity.client.EntityClient;
import com.linkedin.metadata.entity.EntityService;
import com.linkedin.tag.TagProperties;
import com.linkedin.metadata.key.TagKey;
import com.linkedin.mxe.MetadataChangeProposal;
import graphql.schema.DataFetchingEnvironment;
import java.util.concurrent.CompletionException;

import org.mockito.Mockito;
import org.testng.annotations.Test;

import static com.linkedin.datahub.graphql.TestUtils.*;
import static com.linkedin.metadata.Constants.*;
import static org.testng.Assert.*;


public class CreateTagResolverTest {

  private static final CreateTagInput TEST_INPUT = new CreateTagInput(
      "test-id",
      "test-name",
      "test-description"
  );

  @Test
  public void testGetSuccess() throws Exception {
    // Create resolver
    EntityService mockService = Mockito.mock(EntityService.class);
    EntityClient mockClient = Mockito.mock(EntityClient.class);
    Mockito.when(mockClient.ingestProposal(Mockito.any(MetadataChangeProposal.class), Mockito.any(Authentication.class)))
        .thenReturn(String.format("urn:li:tag:%s", TEST_INPUT.getId()));
    CreateTagResolver resolver = new CreateTagResolver(mockClient, mockService);

    // Execute resolver
    QueryContext mockContext = getMockAllowContext();
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(TEST_INPUT);
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);

    resolver.get(mockEnv).get();

    final TagKey key = new TagKey();
    key.setName("test-id");
    TagProperties props = new TagProperties();
    props.setDescription("test-description");
    props.setName("test-name");
    final MetadataChangeProposal proposal = MutationUtils.buildMetadataChangeProposalWithKey(key, TAG_ENTITY_NAME,
        TAG_PROPERTIES_ASPECT_NAME, props);

    // Not ideal to match against "any", but we don't know the auto-generated execution request id
    Mockito.verify(mockClient, Mockito.times(1)).ingestProposal(
        Mockito.eq(proposal),
        Mockito.any(Authentication.class),
        Mockito.eq(false)
    );
  }

  @Test
  public void testGetUnauthorized() throws Exception {
    // Create resolver
    EntityService mockService = Mockito.mock(EntityService.class);
    EntityClient mockClient = Mockito.mock(EntityClient.class);
    CreateTagResolver resolver = new CreateTagResolver(mockClient, mockService);

    // Execute resolver
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    QueryContext mockContext = getMockDenyContext();
    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(TEST_INPUT);
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);

    assertThrows(CompletionException.class, () -> resolver.get(mockEnv).join());
    Mockito.verify(mockClient, Mockito.times(0)).ingestProposal(
        Mockito.any(),
        Mockito.any(Authentication.class));
  }

  @Test
  public void testGetEntityClientException() throws Exception {
    // Create resolver
    EntityService mockService = Mockito.mock(EntityService.class);
    EntityClient mockClient = Mockito.mock(EntityClient.class);
    Mockito.doThrow(RuntimeException.class).when(mockClient).ingestProposal(
        Mockito.any(),
        Mockito.any(Authentication.class),
        Mockito.eq(false));
    CreateTagResolver resolver = new CreateTagResolver(mockClient, mockService);

    // Execute resolver
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    QueryContext mockContext = getMockAllowContext();
    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(TEST_INPUT);
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);

    assertThrows(CompletionException.class, () -> resolver.get(mockEnv).join());
  }
}