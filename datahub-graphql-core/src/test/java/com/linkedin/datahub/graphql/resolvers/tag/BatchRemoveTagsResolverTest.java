package com.linkedin.datahub.graphql.resolvers.tag;

import com.google.common.collect.ImmutableList;
import com.linkedin.common.AuditStamp;
import com.linkedin.common.GlobalTags;
import com.linkedin.common.TagAssociation;
import com.linkedin.common.TagAssociationArray;
import com.linkedin.common.urn.TagUrn;
import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.generated.BatchRemoveTagsInput;
import com.linkedin.datahub.graphql.generated.ResourceRefInput;
import com.linkedin.datahub.graphql.resolvers.mutate.BatchRemoveTagsResolver;
import com.linkedin.datahub.graphql.resolvers.mutate.MutationUtils;
import com.linkedin.events.metadata.ChangeType;
import com.linkedin.metadata.Constants;
import com.linkedin.metadata.entity.EntityService;
import com.linkedin.metadata.utils.GenericRecordUtils;
import com.linkedin.mxe.MetadataChangeProposal;
import graphql.schema.DataFetchingEnvironment;
import java.util.Collections;
import java.util.concurrent.CompletionException;
import org.mockito.Mockito;
import org.testng.annotations.Test;

import static com.linkedin.datahub.graphql.TestUtils.*;
import static com.linkedin.metadata.Constants.*;
import static org.testng.Assert.*;


public class BatchRemoveTagsResolverTest {

  private static final String TEST_ENTITY_URN_1 = "urn:li:dataset:(urn:li:dataPlatform:mysql,my-test,PROD)";
  private static final String TEST_ENTITY_URN_2 = "urn:li:dataset:(urn:li:dataPlatform:mysql,my-test-2,PROD)";
  private static final String TEST_TAG_1_URN = "urn:li:tag:test-id-1";
  private static final String TEST_TAG_2_URN = "urn:li:tag:test-id-2";

  @Test
  public void testGetSuccessNoExistingTags() throws Exception {
    EntityService mockService = Mockito.mock(EntityService.class);

    Mockito.when(mockService.getAspect(
        Mockito.eq(UrnUtils.getUrn(TEST_ENTITY_URN_1)),
        Mockito.eq(Constants.GLOBAL_TAGS_ASPECT_NAME),
        Mockito.eq(0L)))
        .thenReturn(null);
    Mockito.when(mockService.getAspect(
        Mockito.eq(UrnUtils.getUrn(TEST_ENTITY_URN_2)),
        Mockito.eq(Constants.GLOBAL_TAGS_ASPECT_NAME),
        Mockito.eq(0L)))
        .thenReturn(null);

    Mockito.when(mockService.exists(Urn.createFromString(TEST_ENTITY_URN_1))).thenReturn(true);
    Mockito.when(mockService.exists(Urn.createFromString(TEST_ENTITY_URN_2))).thenReturn(true);

    Mockito.when(mockService.exists(Urn.createFromString(TEST_TAG_1_URN))).thenReturn(true);
    Mockito.when(mockService.exists(Urn.createFromString(TEST_TAG_2_URN))).thenReturn(true);

    BatchRemoveTagsResolver resolver = new BatchRemoveTagsResolver(mockService);

    // Execute resolver
    QueryContext mockContext = getMockAllowContext();
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    BatchRemoveTagsInput input = new BatchRemoveTagsInput(ImmutableList.of(
        TEST_TAG_1_URN,
        TEST_TAG_2_URN
    ), ImmutableList.of(
        new ResourceRefInput(TEST_ENTITY_URN_1, null, null),
        new ResourceRefInput(TEST_ENTITY_URN_2, null, null)));
    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(input);
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);
    assertTrue(resolver.get(mockEnv).get());

    final GlobalTags emptyTags = new GlobalTags().setTags(new TagAssociationArray(Collections.emptyList()));

    final MetadataChangeProposal proposal1 = MutationUtils.buildMetadataChangeProposalWithUrn(Urn.createFromString(TEST_ENTITY_URN_1),
        GLOBAL_TAGS_ASPECT_NAME, emptyTags);

    Mockito.verify(mockService, Mockito.times(1)).ingestProposal(
        Mockito.eq(proposal1),
        Mockito.any(AuditStamp.class), Mockito.eq(false)
    );

    final MetadataChangeProposal proposal2 = MutationUtils.buildMetadataChangeProposalWithUrn(Urn.createFromString(TEST_ENTITY_URN_2),
        GLOBAL_TAGS_ASPECT_NAME, emptyTags);
    proposal2.setEntityUrn(Urn.createFromString(TEST_ENTITY_URN_2));
    proposal2.setEntityType(Constants.DATASET_ENTITY_NAME);
    proposal2.setAspectName(Constants.GLOBAL_TAGS_ASPECT_NAME);
    proposal2.setAspect(GenericRecordUtils.serializeAspect(emptyTags));
    proposal2.setChangeType(ChangeType.UPSERT);

    verifyIngestProposal(mockService, 1, proposal2);
  }

  @Test
  public void testGetSuccessExistingTags() throws Exception {
    EntityService mockService = Mockito.mock(EntityService.class);

    final GlobalTags oldTags1 = new GlobalTags().setTags(new TagAssociationArray(ImmutableList.of(
        new TagAssociation().setTag(TagUrn.createFromString(TEST_TAG_1_URN)),
        new TagAssociation().setTag(TagUrn.createFromString(TEST_TAG_2_URN))
    )));

    Mockito.when(mockService.getAspect(
        Mockito.eq(UrnUtils.getUrn(TEST_ENTITY_URN_1)),
        Mockito.eq(Constants.GLOBAL_TAGS_ASPECT_NAME),
        Mockito.eq(0L)))
        .thenReturn(oldTags1);

    final GlobalTags oldTags2 = new GlobalTags().setTags(new TagAssociationArray(ImmutableList.of(
        new TagAssociation().setTag(TagUrn.createFromString(TEST_TAG_1_URN))
    )));

    Mockito.when(mockService.getAspect(
        Mockito.eq(UrnUtils.getUrn(TEST_ENTITY_URN_2)),
        Mockito.eq(Constants.GLOBAL_TAGS_ASPECT_NAME),
        Mockito.eq(0L)))
        .thenReturn(oldTags2);

    Mockito.when(mockService.exists(Urn.createFromString(TEST_ENTITY_URN_1))).thenReturn(true);
    Mockito.when(mockService.exists(Urn.createFromString(TEST_ENTITY_URN_2))).thenReturn(true);

    Mockito.when(mockService.exists(Urn.createFromString(TEST_TAG_1_URN))).thenReturn(true);
    Mockito.when(mockService.exists(Urn.createFromString(TEST_TAG_2_URN))).thenReturn(true);

    BatchRemoveTagsResolver resolver = new BatchRemoveTagsResolver(mockService);

    // Execute resolver
    QueryContext mockContext = getMockAllowContext();
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    BatchRemoveTagsInput input = new BatchRemoveTagsInput(ImmutableList.of(
        TEST_TAG_1_URN,
        TEST_TAG_2_URN
    ), ImmutableList.of(
        new ResourceRefInput(TEST_ENTITY_URN_1, null, null),
        new ResourceRefInput(TEST_ENTITY_URN_2, null, null)));
    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(input);
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);
    assertTrue(resolver.get(mockEnv).get());

    final GlobalTags emptyTags = new GlobalTags().setTags(new TagAssociationArray(Collections.emptyList()));

    final MetadataChangeProposal proposal1 = MutationUtils.buildMetadataChangeProposalWithUrn(Urn.createFromString(TEST_ENTITY_URN_1),
        GLOBAL_TAGS_ASPECT_NAME, emptyTags);

    Mockito.verify(mockService, Mockito.times(1)).ingestProposal(
        Mockito.eq(proposal1),
        Mockito.any(AuditStamp.class), Mockito.eq(false)
    );

    final MetadataChangeProposal proposal2 = MutationUtils.buildMetadataChangeProposalWithUrn(Urn.createFromString(TEST_ENTITY_URN_2),
        GLOBAL_TAGS_ASPECT_NAME, emptyTags);

    verifyIngestProposal(mockService, 1, proposal2);
  }

  @Test
  public void testGetFailureResourceDoesNotExist() throws Exception {
    EntityService mockService = Mockito.mock(EntityService.class);

    Mockito.when(mockService.getAspect(
        Mockito.eq(UrnUtils.getUrn(TEST_ENTITY_URN_1)),
        Mockito.eq(Constants.GLOBAL_TAGS_ASPECT_NAME),
        Mockito.eq(0L)))
        .thenReturn(null);
    Mockito.when(mockService.getAspect(
        Mockito.eq(UrnUtils.getUrn(TEST_ENTITY_URN_2)),
        Mockito.eq(Constants.GLOBAL_TAGS_ASPECT_NAME),
        Mockito.eq(0L)))
        .thenReturn(null);

    Mockito.when(mockService.exists(Urn.createFromString(TEST_ENTITY_URN_1))).thenReturn(false);
    Mockito.when(mockService.exists(Urn.createFromString(TEST_ENTITY_URN_2))).thenReturn(true);
    Mockito.when(mockService.exists(Urn.createFromString(TEST_TAG_1_URN))).thenReturn(true);

    BatchRemoveTagsResolver resolver = new BatchRemoveTagsResolver(mockService);

    // Execute resolver
    QueryContext mockContext = getMockAllowContext();
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    BatchRemoveTagsInput input = new BatchRemoveTagsInput(ImmutableList.of(
        TEST_TAG_1_URN,
        TEST_TAG_2_URN
    ), ImmutableList.of(
        new ResourceRefInput(TEST_ENTITY_URN_1, null, null),
        new ResourceRefInput(TEST_ENTITY_URN_2, null, null)));
    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(input);
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);

    assertThrows(CompletionException.class, () -> resolver.get(mockEnv).join());
    Mockito.verify(mockService, Mockito.times(0)).ingestProposal(
        Mockito.any(),
        Mockito.any(AuditStamp.class), Mockito.anyBoolean());
  }

  @Test
  public void testGetUnauthorized() throws Exception {
    EntityService mockService = Mockito.mock(EntityService.class);

    BatchRemoveTagsResolver resolver = new BatchRemoveTagsResolver(mockService);

    // Execute resolver
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    BatchRemoveTagsInput input = new BatchRemoveTagsInput(ImmutableList.of(
        TEST_TAG_1_URN,
        TEST_TAG_2_URN
    ), ImmutableList.of(
        new ResourceRefInput(TEST_ENTITY_URN_1, null, null),
        new ResourceRefInput(TEST_ENTITY_URN_2, null, null)));
    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(input);
    QueryContext mockContext = getMockDenyContext();
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);

    assertThrows(CompletionException.class, () -> resolver.get(mockEnv).join());
    Mockito.verify(mockService, Mockito.times(0)).ingestProposal(
        Mockito.any(),
        Mockito.any(AuditStamp.class), Mockito.anyBoolean());
  }

  @Test
  public void testGetEntityClientException() throws Exception {
    EntityService mockService = Mockito.mock(EntityService.class);

    Mockito.doThrow(RuntimeException.class).when(mockService).ingestProposal(
        Mockito.any(),
        Mockito.any(AuditStamp.class), Mockito.anyBoolean());

    BatchRemoveTagsResolver resolver = new BatchRemoveTagsResolver(mockService);

    // Execute resolver
    DataFetchingEnvironment mockEnv = Mockito.mock(DataFetchingEnvironment.class);
    QueryContext mockContext = getMockAllowContext();
    BatchRemoveTagsInput input = new BatchRemoveTagsInput(ImmutableList.of(
        TEST_TAG_1_URN,
        TEST_TAG_2_URN
    ), ImmutableList.of(
        new ResourceRefInput(TEST_ENTITY_URN_1, null, null),
        new ResourceRefInput(TEST_ENTITY_URN_2, null, null)));
    Mockito.when(mockEnv.getArgument(Mockito.eq("input"))).thenReturn(input);
    Mockito.when(mockEnv.getContext()).thenReturn(mockContext);

    assertThrows(CompletionException.class, () -> resolver.get(mockEnv).join());
  }
}