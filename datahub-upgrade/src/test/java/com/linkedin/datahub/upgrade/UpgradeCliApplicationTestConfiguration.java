package com.linkedin.datahub.upgrade;

import com.linkedin.gms.factory.auth.SystemAuthenticationFactory;
import com.linkedin.metadata.entity.EntityService;
import com.linkedin.metadata.graph.GraphService;
import com.linkedin.metadata.models.registry.ConfigEntityRegistry;
import com.linkedin.metadata.models.registry.EntityRegistry;
import com.linkedin.metadata.search.SearchService;
import io.ebean.EbeanServer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;

@TestConfiguration
@Import(value = {SystemAuthenticationFactory.class})
public class UpgradeCliApplicationTestConfiguration {

    @MockBean
    private UpgradeCli upgradeCli;

    @MockBean
    private EbeanServer ebeanServer;

    @MockBean
    private EntityService _entityService;

    @MockBean
    private SearchService searchService;

    @MockBean
    private GraphService graphService;

    @MockBean
    private EntityRegistry entityRegistry;

    @MockBean
    ConfigEntityRegistry configEntityRegistry;
}
