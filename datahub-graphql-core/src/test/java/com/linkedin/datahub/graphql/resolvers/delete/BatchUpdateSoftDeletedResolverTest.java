package com.linkedin.datahub.graphql.resolvers.delete;

import com.google.common.collect.ImmutableList;
import com.linkedin.common.AuditStamp;
import com.linkedin.common.Status;
import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.generated.BatchUpdateSoftDeletedInput;
import com.linkedin.datahub.graphql.resolvers.mutate.BatchUpdateSoftDeletedResolver;
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


public class BatchUpdateSoftDeletedResolverTest {

  private static final String TEST_ENTITY_URN_1 = "urn:li:dataset:(urn:li:dataPlatform:mysql,my-test,PROD)";
  private static final String TEST_ENTITY_URN_2 = "urn:li:dataset:(urn:li:dataPlatform:mysql,my-test-2,PROD)";

  @Test
  public void testGetSuccessNoExistingStatus() throws Exception {
    EntityService mockService = Mockito.mock(EntityService.class);

    Mockito.when(mockService.getAspect(
        Mockito.eq(UrnUtils.getUrn(TEST_ENTITY_URN_1)),
        Mockito.eq(Constants.STATUS_ASPECT_NAME),
        Mockito.eq(0L)))
        .thenReturn(null);

    Mockito.when(mockService.getAspect(
        Mockito.eq(UrnUtils.getUrn(TEST_ENTITY_URN_2)),
        Mockito.eq(Constants.STATUS_ASPECT_NAME),
        Mockito.eq(0L)))
        .thenReturn(null);


    Mockito.when(mockService.exists(Urn.createFromString(TEST_ENTITY_URN_1))).thenReturn(true);
    Mockito.when(mockService.exists(Urn.createFromString(TEST_ENTITY_URN_2))).thenReturn(true);

    BatchUpdateSoftDeletedResolver resolver = new BatchUpdateSoftDeletedResolver(mockService);

    // Execute resolver
    QueryContext mockContext = getMockAllowContext();
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    BatchUpdateSoftDeletedInput input = new BatchUpdateSoftDeletedInput(ImmutableList.of(TEST_ENTITY_URN_1, TEST_ENTITY_URN_2), true);
    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(input);
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);
    assertTrue(resolver.get(mockEnv).get());

    final Status newStatus = new Status().setRemoved(true);

    final MetadataChangeProposal proposal1 = MutationUtils.buildMetadataChangeProposalWithUrn(Urn.createFromString(TEST_ENTITY_URN_1),
        STATUS_ASPECT_NAME, newStatus);

    verifyIngestProposal(mockService, 1, proposal1);

    final MetadataChangeProposal proposal2 = MutationUtils.buildMetadataChangeProposalWithUrn(Urn.createFromString(TEST_ENTITY_URN_2),
        STATUS_ASPECT_NAME, newStatus);

    verifyIngestProposal(mockService, 1, proposal2);
  }

  @Test
  public void testGetSuccessExistingStatus() throws Exception {
    final Status originalStatus = new Status().setRemoved(true);

    EntityService mockService = Mockito.mock(EntityService.class);

    Mockito.when(mockService.getAspect(
        Mockito.eq(UrnUtils.getUrn(TEST_ENTITY_URN_1)),
        Mockito.eq(Constants.STATUS_ASPECT_NAME),
        Mockito.eq(0L)))
        .thenReturn(originalStatus);

    Mockito.when(mockService.getAspect(
        Mockito.eq(UrnUtils.getUrn(TEST_ENTITY_URN_2)),
        Mockito.eq(Constants.STATUS_ASPECT_NAME),
        Mockito.eq(0L)))
        .thenReturn(originalStatus);

    Mockito.when(mockService.exists(Urn.createFromString(TEST_ENTITY_URN_1))).thenReturn(true);
    Mockito.when(mockService.exists(Urn.createFromString(TEST_ENTITY_URN_2))).thenReturn(true);

    BatchUpdateSoftDeletedResolver resolver = new BatchUpdateSoftDeletedResolver(mockService);

    // Execute resolver
    QueryContext mockContext = getMockAllowContext();
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    BatchUpdateSoftDeletedInput input = new BatchUpdateSoftDeletedInput(ImmutableList.of(TEST_ENTITY_URN_1, TEST_ENTITY_URN_2), false);
    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(input);
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);
    assertTrue(resolver.get(mockEnv).get());

    final Status newStatus = new Status().setRemoved(false);

    final MetadataChangeProposal proposal1 = MutationUtils.buildMetadataChangeProposalWithUrn(Urn.createFromString(TEST_ENTITY_URN_1),
        STATUS_ASPECT_NAME, newStatus);

    verifyIngestProposal(mockService, 1, proposal1);

    final MetadataChangeProposal proposal2 = MutationUtils.buildMetadataChangeProposalWithUrn(Urn.createFromString(TEST_ENTITY_URN_2),
        STATUS_ASPECT_NAME, newStatus);

    verifyIngestProposal(mockService, 1, proposal2);
  }

  @Test
  public void testGetFailureResourceDoesNotExist() throws Exception {
    EntityService mockService = Mockito.mock(EntityService.class);

    Mockito.when(mockService.getAspect(
        Mockito.eq(UrnUtils.getUrn(TEST_ENTITY_URN_1)),
        Mockito.eq(Constants.STATUS_ASPECT_NAME),
        Mockito.eq(0L)))
        .thenReturn(null);
    Mockito.when(mockService.getAspect(
        Mockito.eq(UrnUtils.getUrn(TEST_ENTITY_URN_2)),
        Mockito.eq(Constants.STATUS_ASPECT_NAME),
        Mockito.eq(0L)))
        .thenReturn(null);

    Mockito.when(mockService.exists(Urn.createFromString(TEST_ENTITY_URN_1))).thenReturn(false);
    Mockito.when(mockService.exists(Urn.createFromString(TEST_ENTITY_URN_2))).thenReturn(true);

    BatchUpdateSoftDeletedResolver resolver = new BatchUpdateSoftDeletedResolver(mockService);

    // Execute resolver
    QueryContext mockContext = getMockAllowContext();
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    BatchUpdateSoftDeletedInput input = new BatchUpdateSoftDeletedInput(ImmutableList.of(TEST_ENTITY_URN_1, TEST_ENTITY_URN_2), false);

    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(input);
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);

    assertThrows(CompletionException.class, () -> resolver.get(mockEnv).join());
    verifyNoIngestProposal(mockService);
  }

  @Test
  public void testGetUnauthorized() throws Exception {
    EntityService mockService = Mockito.mock(EntityService.class);

    BatchUpdateSoftDeletedResolver resolver = new BatchUpdateSoftDeletedResolver(mockService);

    // Execute resolver
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    BatchUpdateSoftDeletedInput input = new BatchUpdateSoftDeletedInput(ImmutableList.of(TEST_ENTITY_URN_1, TEST_ENTITY_URN_2), false);

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

    BatchUpdateSoftDeletedResolver resolver = new BatchUpdateSoftDeletedResolver(mockService);

    // Execute resolver
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    QueryContext mockContext = getMockAllowContext();
    BatchUpdateSoftDeletedInput input = new BatchUpdateSoftDeletedInput(ImmutableList.of(TEST_ENTITY_URN_1, TEST_ENTITY_URN_2), false);

    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(input);
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);

    assertThrows(CompletionException.class, () -> resolver.get(mockEnv).join());
  }
}