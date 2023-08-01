package com.datahub.authorization;

import com.datahub.authentication.Authentication;
import com.google.common.collect.ImmutableSet;
import com.linkedin.common.Owner;
import com.linkedin.common.Ownership;
import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.data.template.StringArray;
import com.linkedin.entity.EntityResponse;
import com.linkedin.entity.EnvelopedAspect;
import com.linkedin.entity.EnvelopedAspectMap;
import com.linkedin.entity.client.EntityClient;
import com.linkedin.identity.GroupMembership;
import com.linkedin.identity.NativeGroupMembership;
import com.linkedin.identity.RoleMembership;
import com.linkedin.metadata.Constants;
import com.linkedin.metadata.authorization.PoliciesConfig;
import com.linkedin.policy.DataHubActorFilter;
import com.linkedin.policy.DataHubPolicyInfo;
import com.linkedin.policy.DataHubResourceFilter;
import com.linkedin.policy.PolicyMatchCondition;
import com.linkedin.policy.PolicyMatchCriterion;
import com.linkedin.policy.PolicyMatchCriterionArray;
import com.linkedin.policy.PolicyMatchFilter;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.linkedin.metadata.Constants.*;


@Slf4j
@RequiredArgsConstructor
public class PolicyEngine {

  private final Authentication _systemAuthentication;
  private final EntityClient _entityClient;

  public PolicyEvaluationResult evaluatePolicy(
      final DataHubPolicyInfo policy,
      final String actorStr,
      final String privilege,
      final Optional<ResolvedResourceSpec> resource) {
    try {
      // Currently Actor must be an urn. Consider whether this contract should be pushed up.
      final Urn actor = Urn.createFromString(actorStr);
      return evaluatePolicy(policy, actor, privilege, resource);
    } catch (URISyntaxException e) {
      log.error(String.format("Failed to bind actor %s to an URN. Actors must be URNs. Denying the authorization request", actorStr));
      return PolicyEvaluationResult.DENIED;
    }
  }

  public PolicyEvaluationResult evaluatePolicy(
      final DataHubPolicyInfo policy,
      final Urn actor,
      final String privilege,
      final Optional<ResolvedResourceSpec> resource) {

    final PolicyEvaluationContext context = new PolicyEvaluationContext();
    log.debug("Evaluating policy {}", policy.getDisplayName());

    // If the privilege is not in scope, deny the request.
    if (!isPrivilegeMatch(privilege, policy.getPrivileges(), context)) {
      log.debug("Policy denied based on irrelevant privileges {} for {}", policy.getPrivileges(), privilege);
      return PolicyEvaluationResult.DENIED;
    }

    // If policy is not applicable, deny the request
    if (!isPolicyApplicable(policy, actor, resource, context)) {
      log.debug("Policy does not applicable for actor {} and resource {}", actor, resource);
      return PolicyEvaluationResult.DENIED;
    }

    // All portions of the Policy match. Grant the request.
    return PolicyEvaluationResult.GRANTED;
  }

  public PolicyActors getMatchingActors(
      final DataHubPolicyInfo policy,
      final Optional<ResolvedResourceSpec> resource) {
    final List<Urn> users = new ArrayList<>();
    final List<Urn> groups = new ArrayList<>();
    boolean allUsers = false;
    boolean allGroups = false;
    if (policyMatchesResource(policy, resource)) {
      // Step 3: For each matching policy, find actors that are authorized.
      final DataHubActorFilter actorFilter = policy.getActors();

      // 0. Determine if we have a wildcard policy.
      if (actorFilter.isAllUsers()) {
        allUsers = true;
      }
      if (actorFilter.isAllUsers()) {
        allGroups = true;
      }

      // 1. Populate actors listed on the policy directly.
      if (actorFilter.getUsers() != null) {
        users.addAll(actorFilter.getUsers());
      }
      if (actorFilter.getGroups() != null) {
        groups.addAll(actorFilter.getGroups());
      }

      // 2. Fetch Actors based on resource ownership.
      if (actorFilter.isResourceOwners() && resource.isPresent()) {
        Set<String> owners = resource.get().getOwners();
        users.addAll(userOwners(owners));
        groups.addAll(groupOwners(owners));
      }
    }
    return new PolicyActors(users, groups, allUsers, allGroups);
  }

  private boolean isPolicyApplicable(
      final DataHubPolicyInfo policy,
      final Urn actor,
      final Optional<ResolvedResourceSpec> resource,
      final PolicyEvaluationContext context
  ) {

    // If policy is inactive, simply return DENY.
    if (PoliciesConfig.INACTIVE_POLICY_STATE.equals(policy.getState())) {
      return false;
    }

    // If the resource is not in scope, deny the request.
    if (!isResourceMatch(policy.getType(), policy.getResources(), resource, context)) {
      return false;
    }

    // If the actor does not match, deny the request.
    if (!isActorMatch(actor, policy.getActors(), resource, context)) {
      return false;
    }

    return true;
  }

  public List<String> getGrantedPrivileges(
      final List<DataHubPolicyInfo> policies,
      final Urn actor,
      final Optional<ResolvedResourceSpec> resource) {
    PolicyEvaluationContext context = new PolicyEvaluationContext();
    return policies.stream()
        .filter(policy -> isPolicyApplicable(policy, actor, resource, context))
        .flatMap(policy -> policy.getPrivileges().stream())
        .distinct()
        .collect(Collectors.toList());
  }

  /**
   * Returns true if the policy matches the resource spec, false otherwise.
   *
   * If the policy is of type "PLATFORM", the resource will always match (since there's no resource).
   * If the policy is of type "METADATA", the resourceSpec parameter will be matched against the
   * resource filter defined on the policy.
   */
  public Boolean policyMatchesResource(final DataHubPolicyInfo policy, final Optional<ResolvedResourceSpec> resourceSpec) {
    return isResourceMatch(policy.getType(), policy.getResources(), resourceSpec,
        new PolicyEvaluationContext());
  }

  /**
   * Returns true if the privilege portion of a DataHub policy matches a the privilege being evaluated, false otherwise.
   */
  private boolean isPrivilegeMatch(
      final String requestPrivilege,
      final List<String> policyPrivileges,
      final PolicyEvaluationContext context) {
    return policyPrivileges.contains(requestPrivilege);
  }

  /**
   * Returns true if the resource portion of a DataHub policy matches a the resource being evaluated, false otherwise.
   */
  private boolean isResourceMatch(
      final String policyType,
      final @Nullable DataHubResourceFilter policyResourceFilter,
      final Optional<ResolvedResourceSpec> requestResource,
      final PolicyEvaluationContext context) {
    if (PoliciesConfig.PLATFORM_POLICY_TYPE.equals(policyType)) {
      // Currently, platform policies have no associated resource.
      return true;
    }
    if (policyResourceFilter == null) {
      // No resource defined on the policy.
      return true;
    }
    if (!requestResource.isPresent()) {
      // Resource filter present in policy, but no resource spec provided.
      log.debug("Resource filter present in policy, but no resource spec provided.");
      return false;
    }
    final PolicyMatchFilter filter = getFilter(policyResourceFilter);
    return checkFilter(filter, requestResource.get());
  }

  /**
   * Get filter object from policy resource filter. Make sure it is backward compatible by constructing PolicyMatchFilter object
   * from other fields if the filter field is not set
   */
  private PolicyMatchFilter getFilter(DataHubResourceFilter policyResourceFilter) {
    if (policyResourceFilter.hasFilter()) {
      return policyResourceFilter.getFilter();
    }
    PolicyMatchCriterionArray criteria = new PolicyMatchCriterionArray();
    if (policyResourceFilter.hasType()) {
      criteria.add(new PolicyMatchCriterion().setField(ResourceFieldType.RESOURCE_TYPE.name())
          .setValues(new StringArray(Collections.singletonList(policyResourceFilter.getType()))));
    }
    if (policyResourceFilter.hasType() && policyResourceFilter.hasResources()
        && !policyResourceFilter.isAllResources()) {
      criteria.add(
          new PolicyMatchCriterion().setField(ResourceFieldType.RESOURCE_URN.name()).setValues(policyResourceFilter.getResources()));
    }
    return new PolicyMatchFilter().setCriteria(criteria);
  }

  private boolean checkFilter(final PolicyMatchFilter filter, final ResolvedResourceSpec resource) {
    return filter.getCriteria().stream().allMatch(criterion -> checkCriterion(criterion, resource));
  }

  private boolean checkCriterion(final PolicyMatchCriterion criterion, final ResolvedResourceSpec resource) {
    ResourceFieldType resourceFieldType;
    try {
      resourceFieldType = ResourceFieldType.valueOf(criterion.getField().toUpperCase());
    } catch (IllegalArgumentException e) {
      log.error("Unsupported field type {}", criterion.getField());
      return false;
    }

    Set<String> fieldValues = resource.getFieldValues(resourceFieldType);
    return criterion.getValues()
        .stream()
        .anyMatch(filterValue -> checkCondition(fieldValues, filterValue, criterion.getCondition()));
  }

  private boolean checkCondition(Set<String> fieldValues, String filterValue, PolicyMatchCondition condition) {
    if (condition == PolicyMatchCondition.EQUALS) {
      return fieldValues.contains(filterValue);
    }
    log.error("Unsupported condition {}", condition);
    return false;
  }

  /**
   * Returns true if the actor portion of a DataHub policy matches a the actor being evaluated, false otherwise.
   */
  private boolean isActorMatch(
      final Urn actor,
      final DataHubActorFilter actorFilter,
      final Optional<ResolvedResourceSpec> resourceSpec,
      final PolicyEvaluationContext context) {

    // 1. If the actor is a matching "User" in the actor filter, return true immediately.
    if (isUserMatch(actor, actorFilter)) {
      return true;
    }

    // 2. If the actor is in a matching "Group" in the actor filter, return true immediately.
    if (isGroupMatch(actor, actorFilter, context)) {
      return true;
    }

    // 3. If the actor is the owner, either directly or indirectly via a group, return true immediately.
    if (isOwnerMatch(actor, actorFilter, resourceSpec, context)) {
      return true;
    }

    // 4. If the actor is in a matching "Role" in the actor filter, return true immediately.
    return isRoleMatch(actor, actorFilter, context);
  }

  private boolean isUserMatch(final Urn actor, final DataHubActorFilter actorFilter) {
    // If the actor is a matching "User" in the actor filter, return true immediately.
    return actorFilter.isAllUsers() || (actorFilter.hasUsers() && Objects.requireNonNull(actorFilter.getUsers())
        .stream()
        .anyMatch(user -> user.equals(actor)));
  }

  private boolean isGroupMatch(final Urn actor, final DataHubActorFilter actorFilter, final PolicyEvaluationContext context) {
    // If the actor is in a matching "Group" in the actor filter, return true immediately.
    if (actorFilter.isAllGroups() || actorFilter.hasGroups()) {
      final Set<Urn> groups = resolveGroups(actor, context);
      return actorFilter.isAllGroups() || (actorFilter.hasGroups() && Objects.requireNonNull(actorFilter.getGroups())
          .stream()
          .anyMatch(groups::contains));
    }
    // If there are no groups on the policy, return false for the group match.
    return false;
  }

  private boolean isOwnerMatch(
      final Urn actor,
      final DataHubActorFilter actorFilter,
      final Optional<ResolvedResourceSpec> requestResource,
      final PolicyEvaluationContext context) {
    // If the policy does not apply to owners, or there is no resource to own, return false immediately.
    if (!actorFilter.isResourceOwners() || !requestResource.isPresent()) {
      return false;
    }
    List<Urn> ownershipTypes = actorFilter.getResourceOwnersTypes();
    return isActorOwner(actor, requestResource.get(), ownershipTypes, context);
  }

  private Set<String> getOwnersForType(ResourceSpec resourceSpec, List<Urn> ownershipTypes) {
    Urn entityUrn = UrnUtils.getUrn(resourceSpec.getResource());
    EnvelopedAspect ownershipAspect;
    try {
      EntityResponse response = _entityClient.getV2(entityUrn.getEntityType(), entityUrn,
              Collections.singleton(Constants.OWNERSHIP_ASPECT_NAME), _systemAuthentication);
      if (response == null || !response.getAspects().containsKey(Constants.OWNERSHIP_ASPECT_NAME)) {
        return Collections.emptySet();
      }
      ownershipAspect = response.getAspects().get(Constants.OWNERSHIP_ASPECT_NAME);
    } catch (Exception e) {
      log.error("Error while retrieving ownership aspect for urn {}", entityUrn, e);
      return Collections.emptySet();
    }
    Ownership ownership = new Ownership(ownershipAspect.getValue().data());
    Stream<Owner> ownersStream = ownership.getOwners().stream();
    if (ownershipTypes != null) {
      ownersStream = ownersStream.filter(owner -> ownershipTypes.contains(owner.getTypeUrn()));
    }
    return ownersStream.map(owner -> owner.getOwner().toString()).collect(Collectors.toSet());
  }

  private boolean isActorOwner(Urn actor, ResolvedResourceSpec resourceSpec, List<Urn> ownershipTypes, PolicyEvaluationContext context) {
    Set<String> owners = this.getOwnersForType(resourceSpec.getSpec(), ownershipTypes);
    if (isUserOwner(actor, owners)) {
      return true;
    }
    final Set<Urn> groups = resolveGroups(actor, context);
    if (isGroupOwner(groups, owners)) {
      return true;
    }
    return false;
  }

  private boolean isUserOwner(Urn actor, Set<String> owners) {
    return owners.contains(actor.toString());
  }

  private boolean isGroupOwner(Set<Urn> groups, Set<String> owners) {
    return groups.stream().anyMatch(group -> owners.contains(group.toString()));
  }

  private boolean isRoleMatch(final Urn actor, final DataHubActorFilter actorFilter,
      final PolicyEvaluationContext context) {
    // Can immediately return false if the actor filter does not have any roles
    if (!actorFilter.hasRoles()) {
      return false;
    }
    // If the actor has a matching "Role" in the actor filter, return true immediately.
    Set<Urn> actorRoles = resolveRoles(actor, context);
    return Objects.requireNonNull(actorFilter.getRoles())
        .stream()
        .anyMatch(actorRoles::contains);
  }

  private Set<Urn> resolveRoles(Urn actor, PolicyEvaluationContext context) {
    if (context.roles != null) {
      return context.roles;
    }

    Set<Urn> roles = new HashSet<>();
    final EnvelopedAspectMap aspectMap;

    try {
      final EntityResponse corpUser = _entityClient.batchGetV2(CORP_USER_ENTITY_NAME, Collections.singleton(actor),
          Collections.singleton(ROLE_MEMBERSHIP_ASPECT_NAME), _systemAuthentication).get(actor);
      if (corpUser == null || !corpUser.hasAspects()) {
        return roles;
      }
      aspectMap = corpUser.getAspects();
    } catch (Exception e) {
      log.error(String.format("Failed to fetch %s for urn %s", ROLE_MEMBERSHIP_ASPECT_NAME, actor), e);
      return roles;
    }

    if (!aspectMap.containsKey(ROLE_MEMBERSHIP_ASPECT_NAME)) {
      return roles;
    }

    RoleMembership roleMembership = new RoleMembership(aspectMap.get(ROLE_MEMBERSHIP_ASPECT_NAME).getValue().data());
    if (roleMembership.hasRoles()) {
      roles.addAll(roleMembership.getRoles());
      context.setRoles(roles);
    }
    return roles;
  }

  private Set<Urn> resolveGroups(Urn actor, PolicyEvaluationContext context) {
    if (context.groups != null) {
      return context.groups;
    }

    Set<Urn> groups = new HashSet<>();
    final EnvelopedAspectMap aspectMap;

    try {
      final EntityResponse corpUser = _entityClient.batchGetV2(CORP_USER_ENTITY_NAME, Collections.singleton(actor),
              ImmutableSet.of(GROUP_MEMBERSHIP_ASPECT_NAME, NATIVE_GROUP_MEMBERSHIP_ASPECT_NAME), _systemAuthentication)
          .get(actor);
      if (corpUser == null || !corpUser.hasAspects()) {
        return groups;
      }
      aspectMap = corpUser.getAspects();
    } catch (Exception e) {
      throw new RuntimeException(String.format("Failed to fetch %s and %s for urn %s", GROUP_MEMBERSHIP_ASPECT_NAME,
          NATIVE_GROUP_MEMBERSHIP_ASPECT_NAME, actor), e);
    }

    Optional<GroupMembership> maybeGroupMembership = resolveGroupMembership(aspectMap);
    maybeGroupMembership.ifPresent(groupMembership -> groups.addAll(groupMembership.getGroups()));

    Optional<NativeGroupMembership> maybeNativeGroupMembership = resolveNativeGroupMembership(aspectMap);
    maybeNativeGroupMembership.ifPresent(
        nativeGroupMembership -> groups.addAll(nativeGroupMembership.getNativeGroups()));

    context.setGroups(groups); // Cache the groups.
    return groups;
  }

  // TODO: Optimization - Cache the group membership. Refresh periodically.
  private Optional<GroupMembership> resolveGroupMembership(final EnvelopedAspectMap aspectMap) {
    if (aspectMap.containsKey(GROUP_MEMBERSHIP_ASPECT_NAME)) {
      return Optional.of(new GroupMembership(aspectMap.get(GROUP_MEMBERSHIP_ASPECT_NAME).getValue().data()));
    }
    return Optional.empty();
  }

  private Optional<NativeGroupMembership> resolveNativeGroupMembership(final EnvelopedAspectMap aspectMap) {
    if (aspectMap.containsKey(NATIVE_GROUP_MEMBERSHIP_ASPECT_NAME)) {
      return Optional.of(
          new NativeGroupMembership(aspectMap.get(NATIVE_GROUP_MEMBERSHIP_ASPECT_NAME).getValue().data()));
    }
    return Optional.empty();
  }

  /**
   * Class used to store state across a single Policy evaluation.
   */
  static class PolicyEvaluationContext {
    private Set<Urn> groups;
    private Set<Urn> roles;

    public void setGroups(Set<Urn> groups) {
      this.groups = groups;
    }

    public void setRoles(Set<Urn> roles) {
      this.roles = roles;
    }
  }

  /**
   * Class used to represent the result of a Policy evaluation
   */
  static class PolicyEvaluationResult {
    public static final PolicyEvaluationResult GRANTED = new PolicyEvaluationResult(true);
    public static final PolicyEvaluationResult DENIED = new PolicyEvaluationResult(false);

    private final boolean isGranted;

    private PolicyEvaluationResult(boolean isGranted) {
      this.isGranted = isGranted;
    }

    public boolean isGranted() {
      return this.isGranted;
    }
  }

  /**
   * Class used to represent all valid users of a policy.
   */
  public static class PolicyActors {
    final List<Urn> _users;
    final List<Urn> _groups;
    final Boolean _allUsers;
    final Boolean _allGroups;

    public PolicyActors(final List<Urn> users, final List<Urn> groups, final Boolean allUsers, final Boolean allGroups) {
      _users = users;
      _groups = groups;
      _allUsers = allUsers;
      _allGroups = allGroups;
    }

    public List<Urn> getUsers() {
      return _users;
    }

    public List<Urn> getGroups() {
      return _groups;
    }

    public Boolean allUsers() {
      return _allUsers;
    }

    public Boolean allGroups() {
      return _allGroups;
    }
  }

  private List<Urn> userOwners(final Set<String> owners) {
    return owners.stream()
        .map(UrnUtils::getUrn)
        .filter(owner -> CORP_USER_ENTITY_NAME.equals(owner.getEntityType()))
        .collect(Collectors.toList());
  }

  private List<Urn> groupOwners(final Set<String> owners) {
    return owners.stream()
        .map(UrnUtils::getUrn)
        .filter(owner -> CORP_GROUP_ENTITY_NAME.equals(owner.getEntityType()))
        .collect(Collectors.toList());
  }
}
