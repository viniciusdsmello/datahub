package com.linkedin.datahub.graphql.resolvers.deprecation;

import com.google.common.collect.ImmutableList;
import com.linkedin.common.AuditStamp;
import com.linkedin.common.Deprecation;
import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.generated.BatchUpdateDeprecationInput;
import com.linkedin.datahub.graphql.generated.ResourceRefInput;
import com.linkedin.datahub.graphql.resolvers.mutate.BatchUpdateDeprecationResolver;
import com.linkedin.datahub.graphql.resolvers.mutate.MutationUtils;
import com.linkedin.metadata.Constants;
import com.linkedin.metadata.entity.EntityService;
import com.linkedin.mxe.MetadataChangeProposal;
import graphql.schema.DataFetchingEnvironment;
import java.util.concurrent.CompletionException;
import org.mockito.Mockito;
import org.testng.annotations.Test;

import static com.linkedin.datahub.graphql.TestUtils.*;
import static com.linkedin.metadata.Constants.*;
import static org.testng.Assert.*;


public class BatchUpdateDeprecationResolverTest {

  private static final String TEST_ENTITY_URN_1 = "urn:li:dataset:(urn:li:dataPlatform:mysql,my-test,PROD)";
  private static final String TEST_ENTITY_URN_2 = "urn:li:dataset:(urn:li:dataPlatform:mysql,my-test-2,PROD)";

  @Test
  public void testGetSuccessNoExistingDeprecation() throws Exception {
    EntityService mockService = Mockito.mock(EntityService.class);

    Mockito.when(mockService.getAspect(
        Mockito.eq(UrnUtils.getUrn(TEST_ENTITY_URN_1)),
        Mockito.eq(Constants.DEPRECATION_ASPECT_NAME),
        Mockito.eq(0L)))
        .thenReturn(null);

    Mockito.when(mockService.getAspect(
        Mockito.eq(UrnUtils.getUrn(TEST_ENTITY_URN_2)),
        Mockito.eq(Constants.DEPRECATION_ASPECT_NAME),
        Mockito.eq(0L)))
        .thenReturn(null);


    Mockito.when(mockService.exists(Urn.createFromString(TEST_ENTITY_URN_1))).thenReturn(true);
    Mockito.when(mockService.exists(Urn.createFromString(TEST_ENTITY_URN_2))).thenReturn(true);

    BatchUpdateDeprecationResolver resolver = new BatchUpdateDeprecationResolver(mockService);

    // Execute resolver
    QueryContext mockContext = getMockAllowContext();
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    BatchUpdateDeprecationInput input = new BatchUpdateDeprecationInput(true, 0L, "test", ImmutableList.of(
        new ResourceRefInput(TEST_ENTITY_URN_1, null, null),
        new ResourceRefInput(TEST_ENTITY_URN_2, null, null)));
    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(input);
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);
    assertTrue(resolver.get(mockEnv).get());

    final Deprecation newDeprecation = new Deprecation()
        .setDeprecated(true)
        .setNote("test")
        .setDecommissionTime(0L)
        .setActor(UrnUtils.getUrn("urn:li:corpuser:test"));

    final MetadataChangeProposal proposal1 = MutationUtils.buildMetadataChangeProposalWithUrn(Urn.createFromString(TEST_ENTITY_URN_1),
        DEPRECATION_ASPECT_NAME, newDeprecation);

    verifyIngestProposal(mockService, 1, proposal1);

    final MetadataChangeProposal proposal2 = MutationUtils.buildMetadataChangeProposalWithUrn(Urn.createFromString(TEST_ENTITY_URN_2),
        DEPRECATION_ASPECT_NAME, newDeprecation);
    verifyIngestProposal(mockService, 1, proposal2);
  }

  @Test
  public void testGetSuccessExistingDeprecation() throws Exception {
    final Deprecation originalDeprecation = new Deprecation()
        .setDeprecated(false)
        .setNote("")
        .setActor(UrnUtils.getUrn("urn:li:corpuser:test"));

    EntityService mockService = Mockito.mock(EntityService.class);

    Mockito.when(mockService.getAspect(
        Mockito.eq(UrnUtils.getUrn(TEST_ENTITY_URN_1)),
        Mockito.eq(Constants.DEPRECATION_ASPECT_NAME),
        Mockito.eq(0L)))
        .thenReturn(originalDeprecation);

    Mockito.when(mockService.getAspect(
        Mockito.eq(UrnUtils.getUrn(TEST_ENTITY_URN_2)),
        Mockito.eq(Constants.DEPRECATION_ASPECT_NAME),
        Mockito.eq(0L)))
        .thenReturn(originalDeprecation);

    Mockito.when(mockService.exists(Urn.createFromString(TEST_ENTITY_URN_1))).thenReturn(true);
    Mockito.when(mockService.exists(Urn.createFromString(TEST_ENTITY_URN_2))).thenReturn(true);

    BatchUpdateDeprecationResolver resolver = new BatchUpdateDeprecationResolver(mockService);

    // Execute resolver
    QueryContext mockContext = getMockAllowContext();
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    BatchUpdateDeprecationInput input = new BatchUpdateDeprecationInput(true, 1L, "test", ImmutableList.of(
        new ResourceRefInput(TEST_ENTITY_URN_1, null, null),
        new ResourceRefInput(TEST_ENTITY_URN_2, null, null)));
    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(input);
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);
    assertTrue(resolver.get(mockEnv).get());

    final Deprecation newDeprecation = new Deprecation()
        .setDeprecated(true)
        .setNote("test")
        .setDecommissionTime(1L)
        .setActor(UrnUtils.getUrn("urn:li:corpuser:test"));

    final MetadataChangeProposal proposal1 = MutationUtils.buildMetadataChangeProposalWithUrn(Urn.createFromString(TEST_ENTITY_URN_1),
        DEPRECATION_ASPECT_NAME, newDeprecation);

    verifyIngestProposal(mockService, 1, proposal1);

    final MetadataChangeProposal proposal2 = MutationUtils.buildMetadataChangeProposalWithUrn(Urn.createFromString(TEST_ENTITY_URN_2),
        DEPRECATION_ASPECT_NAME, newDeprecation);

    verifyIngestProposal(mockService, 1, proposal2);
  }

  @Test
  public void testGetFailureResourceDoesNotExist() throws Exception {
    EntityService mockService = Mockito.mock(EntityService.class);

    Mockito.when(mockService.getAspect(
        Mockito.eq(UrnUtils.getUrn(TEST_ENTITY_URN_1)),
        Mockito.eq(Constants.DEPRECATION_ASPECT_NAME),
        Mockito.eq(0L)))
        .thenReturn(null);
    Mockito.when(mockService.getAspect(
        Mockito.eq(UrnUtils.getUrn(TEST_ENTITY_URN_2)),
        Mockito.eq(Constants.DEPRECATION_ASPECT_NAME),
        Mockito.eq(0L)))
        .thenReturn(null);

    Mockito.when(mockService.exists(Urn.createFromString(TEST_ENTITY_URN_1))).thenReturn(false);
    Mockito.when(mockService.exists(Urn.createFromString(TEST_ENTITY_URN_2))).thenReturn(true);

    BatchUpdateDeprecationResolver resolver = new BatchUpdateDeprecationResolver(mockService);

    // Execute resolver
    QueryContext mockContext = getMockAllowContext();
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    BatchUpdateDeprecationInput input = new BatchUpdateDeprecationInput(true, 1L, "test", ImmutableList.of(
        new ResourceRefInput(TEST_ENTITY_URN_1, null, null),
        new ResourceRefInput(TEST_ENTITY_URN_2, null, null)));
    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(input);
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);

    assertThrows(CompletionException.class, () -> resolver.get(mockEnv).join());
    verifyNoIngestProposal(mockService);
  }

  @Test
  public void testGetUnauthorized() throws Exception {
    EntityService mockService = Mockito.mock(EntityService.class);

    BatchUpdateDeprecationResolver resolver = new BatchUpdateDeprecationResolver(mockService);

    // Execute resolver
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    BatchUpdateDeprecationInput input = new BatchUpdateDeprecationInput(true, 1L, "test", ImmutableList.of(
        new ResourceRefInput(TEST_ENTITY_URN_1, null, null),
        new ResourceRefInput(TEST_ENTITY_URN_2, null, null)));
    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(input);
    QueryContext mockContext = getMockDenyContext();
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);

    assertThrows(CompletionException.class, () -> resolver.get(mockEnv).join());
    verifyNoIngestProposal(mockService);
  }

  @Test
  public void testGetEntityClientException() throws Exception {
    EntityService mockService = Mockito.mock(EntityService.class);

    Mockito.doThrow(RuntimeException.class).when(mockService).ingestProposal(
        Mockito.any(),
        Mockito.any(AuditStamp.class), Mockito.anyBoolean());

    BatchUpdateDeprecationResolver resolver = new BatchUpdateDeprecationResolver(mockService);

    // Execute resolver
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    QueryContext mockContext = getMockAllowContext();
    BatchUpdateDeprecationInput input = new BatchUpdateDeprecationInput(true, 1L, "test", ImmutableList.of(
        new ResourceRefInput(TEST_ENTITY_URN_1, null, null),
        new ResourceRefInput(TEST_ENTITY_URN_2, null, null)));
    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(input);
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);

    assertThrows(CompletionException.class, () -> resolver.get(mockEnv).join());
  }
}