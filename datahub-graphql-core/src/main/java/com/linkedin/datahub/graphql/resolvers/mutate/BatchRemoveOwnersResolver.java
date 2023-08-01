package com.linkedin.datahub.graphql.resolvers.mutate;

import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.datahub.graphql.QueryContext;
import com.linkedin.datahub.graphql.exception.AuthorizationException;
import com.linkedin.datahub.graphql.generated.BatchRemoveOwnersInput;
import com.linkedin.datahub.graphql.generated.ResourceRefInput;
import com.linkedin.datahub.graphql.resolvers.mutate.util.LabelUtils;
import com.linkedin.datahub.graphql.resolvers.mutate.util.OwnerUtils;
import com.linkedin.metadata.entity.EntityService;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.linkedin.datahub.graphql.resolvers.ResolverUtils.*;


@Slf4j
@RequiredArgsConstructor
public class BatchRemoveOwnersResolver implements DataFetcher<CompletableFuture<Boolean>> {

  private final EntityService _entityService;

  @Override
  public CompletableFuture<Boolean> get(DataFetchingEnvironment environment) throws Exception {
    final BatchRemoveOwnersInput input = bindArgument(environment.getArgument("input"), BatchRemoveOwnersInput.class);
    final List<String> owners = input.getOwnerUrns();
    final List<ResourceRefInput> resources = input.getResources();
    final Optional<Urn> maybeOwnershipTypeUrn = input.getOwnershipTypeUrn() == null ? Optional.empty()
        : Optional.of(Urn.createFromString(input.getOwnershipTypeUrn()));
    final QueryContext context = environment.getContext();

    return CompletableFuture.supplyAsync(() -> {

      // First, validate the batch
      validateInputResources(resources, context);

      try {
        // Then execute the bulk remove
        batchRemoveOwners(owners, maybeOwnershipTypeUrn, resources, context);
        return true;
      } catch (Exception e) {
        log.error("Failed to perform update against input {}, {}", input.toString(), e.getMessage());
        throw new RuntimeException(String.format("Failed to perform update against input %s", input.toString()), e);
      }
    });
  }

  private void validateInputResources(List<ResourceRefInput> resources, QueryContext context) {
    for (ResourceRefInput resource : resources) {
      validateInputResource(resource, context);
    }
  }

  private void validateInputResource(ResourceRefInput resource, QueryContext context) {
    final Urn resourceUrn = UrnUtils.getUrn(resource.getResourceUrn());

    if (resource.getSubResource() != null) {
      throw new IllegalArgumentException("Malformed input provided: owners cannot be removed from subresources.");
    }

    if (!OwnerUtils.isAuthorizedToUpdateOwners(context, resourceUrn)) {
      throw new AuthorizationException("Unauthorized to perform this action. Please contact your DataHub administrator.");
    }
    LabelUtils.validateResource(resourceUrn, resource.getSubResource(), resource.getSubResourceType(), _entityService);
  }

  private void batchRemoveOwners(List<String> ownerUrns, Optional<Urn> maybeOwnershipTypeUrn,
      List<ResourceRefInput> resources, QueryContext context) {
    log.debug("Batch removing owners. owners: {}, resources: {}", ownerUrns, resources);
    try {
      OwnerUtils.removeOwnersFromResources(ownerUrns.stream().map(UrnUtils::getUrn).collect(
          Collectors.toList()), maybeOwnershipTypeUrn, resources, UrnUtils.getUrn(context.getActorUrn()), _entityService);
    } catch (Exception e) {
      throw new RuntimeException(String.format("Failed to batch remove Owners %s to resources with urns %s!",
          ownerUrns,
          resources.stream().map(ResourceRefInput::getResourceUrn).collect(Collectors.toList())),
          e);
    }
  }
}