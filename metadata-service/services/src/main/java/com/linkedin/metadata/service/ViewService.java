package com.linkedin.metadata.service;

import com.datahub.authentication.Authentication;
import com.google.common.collect.ImmutableSet;
import com.linkedin.common.AuditStamp;
import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.data.template.SetMode;
import com.linkedin.entity.EntityResponse;
import com.linkedin.entity.client.EntityClient;
import com.linkedin.metadata.Constants;
import com.linkedin.metadata.entity.AspectUtils;
import com.linkedin.metadata.key.DataHubViewKey;
import com.linkedin.metadata.utils.EntityKeyUtils;
import com.linkedin.view.DataHubViewDefinition;
import com.linkedin.view.DataHubViewInfo;
import com.linkedin.view.DataHubViewType;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;


/**
 * This class is used to permit easy CRUD operations on a DataHub View.
 * Currently it supports creating, updating, and removing a View.
 *
 * Note that no Authorization is performed within the service. The expectation
 * is that the caller has already verified the permissions of the active Actor.
 *
 * TODO: Ideally we have some basic caching of the view information inside of this class.
 */
@Slf4j
public class ViewService extends BaseService {

  public ViewService(@Nonnull EntityClient entityClient, @Nonnull Authentication systemAuthentication) {
    super(entityClient, systemAuthentication);
  }

  /**
   * Creates a new DataHub View.
   *
   * Note that this method does not do authorization validation.
   * It is assumed that users of this class have already authorized the operation.
   *
   * @param type the type of the View
   * @param name the name of the View
   * @param description the description of the View
   * @param definition the view definition, a.k.a. the View definition
   * @param authentication the current authentication
   *
   * @return the urn of the newly created View
   */
  public Urn createView(
      @Nonnull DataHubViewType type,
      @Nonnull String name,
      @Nullable String description,
      @Nonnull DataHubViewDefinition definition,
      @Nonnull Authentication authentication,
      long currentTimeMs) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(definition, "definition must not be null");
    Objects.requireNonNull(authentication, "authentication must not be null");

    // 1. Generate a unique id for the new View.
    final DataHubViewKey key = new DataHubViewKey();
    key.setId(UUID.randomUUID().toString());

    // 2. Create a new instance of DataHubViewInfo
    final DataHubViewInfo newView = new DataHubViewInfo();
    newView.setType(type);
    newView.setName(name);
    newView.setDescription(description, SetMode.IGNORE_NULL);
    newView.setDefinition(definition);
    final AuditStamp auditStamp = new AuditStamp()
        .setActor(UrnUtils.getUrn(authentication.getActor().toUrnStr()))
        .setTime(currentTimeMs);
    newView.setCreated(auditStamp);
    newView.setLastModified(auditStamp);


    // 3. Write the new view to GMS, return the new URN.
    try {
      return UrnUtils.getUrn(this.entityClient.ingestProposal(AspectUtils.buildMetadataChangeProposal(
          EntityKeyUtils.convertEntityKeyToUrn(key, Constants.DATAHUB_VIEW_ENTITY_NAME), Constants.DATAHUB_VIEW_INFO_ASPECT_NAME, newView), authentication,
          false));
    } catch (Exception e) {
      throw new RuntimeException("Failed to create View", e);
    }
  }

  /**
   * Updates an existing DataHub View with a specific urn. The overwrites only the fields
   * which are not null (provided).
   *
   * Note that this method does not do authorization validation.
   * It is assumed that users of this class have already authorized the operation.
   *
   * The View with the provided urn must exist, else an {@link IllegalArgumentException} will be
   * thrown.
   *
   * This method will perform a read-modify-write. This can cause concurrent writes
   * to conflict, and overwrite one another. The expected frequency of writes
   * for views is very low, however. TODO: Convert this into a safer patch.
   *
   * @param viewUrn the urn of the View
   * @param name the name of the View
   * @param description the description of the View
   * @param definition the view definition itself
   * @param authentication the current authentication
   * @param currentTimeMs the current time in milliseconds, used for populating the lastUpdatedAt field.
   */
  public void updateView(
      @Nonnull Urn viewUrn,
      @Nullable String name,
      @Nullable String description,
      @Nullable DataHubViewDefinition definition,
      @Nonnull Authentication authentication,
      long currentTimeMs) {
    Objects.requireNonNull(viewUrn, "viewUrn must not be null");
    Objects.requireNonNull(authentication, "authentication must not be null");

    // 1. Check whether the View exists
    DataHubViewInfo existingInfo = getViewInfo(viewUrn, authentication);

    if (existingInfo == null) {
      throw new IllegalArgumentException(String.format("Failed to update View. View with urn %s does not exist.", viewUrn));
    }

    // 2. Apply changes to existing View
    if (name != null) {
      existingInfo.setName(name);
    }
    if (description != null) {
      existingInfo.setDescription(description);
    }
    if (definition != null) {
      existingInfo.setDefinition(definition);
    }

    existingInfo.setLastModified(new AuditStamp()
        .setTime(currentTimeMs)
        .setActor(UrnUtils.getUrn(authentication.getActor().toUrnStr())));

    // 3. Write changes to GMS
    try {
      this.entityClient.ingestProposal(
          AspectUtils.buildMetadataChangeProposal(viewUrn, Constants.DATAHUB_VIEW_INFO_ASPECT_NAME, existingInfo),
          authentication, false);
    } catch (Exception e) {
      throw new RuntimeException(String.format("Failed to update View with urn %s", viewUrn), e);
    }
  }

  /**
   * Deletes an existing DataHub View with a specific urn.
   *
   * Note that this method does not do authorization validation.
   * It is assumed that users of this class have already authorized the operation
   *
   * If the View does not exist, no exception will be thrown.
   *
   * @param viewUrn the urn of the View
   * @param authentication the current authentication
   */
  public void deleteView(
      @Nonnull Urn viewUrn,
      @Nonnull Authentication authentication) {
    try {
      this.entityClient.deleteEntity(
          Objects.requireNonNull(viewUrn, "viewUrn must not be null"),
          Objects.requireNonNull(authentication, "authentication must not be null"));
    } catch (Exception e) {
      throw new RuntimeException(String.format("Failed to delete View with urn %s", viewUrn), e);
    }
  }

  /**
   * Returns an instance of {@link DataHubViewInfo} for the specified View urn,
   * or null if one cannot be found.
   *
   * @param viewUrn the urn of the View
   * @param authentication the authentication to use
   *
   * @return an instance of {@link DataHubViewInfo} for the View, null if it does not exist.
   */
  @Nullable
  public DataHubViewInfo getViewInfo(@Nonnull final Urn viewUrn, @Nonnull final Authentication authentication) {
    Objects.requireNonNull(viewUrn, "viewUrn must not be null");
    Objects.requireNonNull(authentication, "authentication must not be null");
    final EntityResponse response = getViewEntityResponse(viewUrn, authentication);
    if (response != null && response.getAspects().containsKey(Constants.DATAHUB_VIEW_INFO_ASPECT_NAME)) {
      return new DataHubViewInfo(response.getAspects().get(Constants.DATAHUB_VIEW_INFO_ASPECT_NAME).getValue().data());
    }
    // No aspect found
    return null;
  }

  /**
   * Returns an instance of {@link EntityResponse} for the specified View urn,
   * or null if one cannot be found.
   *
   * @param viewUrn the urn of the View
   * @param authentication the authentication to use
   *
   * @return an instance of {@link EntityResponse} for the View, null if it does not exist.
   */
  @Nullable
  public EntityResponse getViewEntityResponse(@Nonnull final Urn viewUrn, @Nonnull final Authentication authentication) {
    Objects.requireNonNull(viewUrn, "viewUrn must not be null");
    Objects.requireNonNull(authentication, "authentication must not be null");
    try {
      return this.entityClient.getV2(
          Constants.DATAHUB_VIEW_ENTITY_NAME,
          viewUrn,
          ImmutableSet.of(Constants.DATAHUB_VIEW_INFO_ASPECT_NAME),
          authentication
      );
    } catch (Exception e) {
      throw new RuntimeException(String.format("Failed to retrieve View with urn %s", viewUrn), e);
    }
  }
}
