/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.admin;

import static org.apache.pulsar.broker.cache.ConfigurationCacheService.POLICIES;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import org.apache.bookkeeper.util.ZkUtils;
import org.apache.pulsar.broker.admin.v1.Namespaces;
import org.apache.pulsar.broker.admin.v1.PersistentTopics;
import org.apache.pulsar.broker.auth.MockedPulsarServiceBaseTest;
import org.apache.pulsar.broker.namespace.NamespaceEphemeralData;
import org.apache.pulsar.broker.namespace.NamespaceService;
import org.apache.pulsar.broker.namespace.OwnershipCache;
import org.apache.pulsar.broker.web.PulsarWebResource;
import org.apache.pulsar.broker.web.RestException;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.common.naming.NamespaceBundle;
import org.apache.pulsar.common.naming.NamespaceBundles;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.policies.data.AuthAction;
import org.apache.pulsar.common.policies.data.BundlesData;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.PersistencePolicies;
import org.apache.pulsar.common.policies.data.Policies;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.apache.pulsar.common.policies.data.RetentionPolicies;
import org.apache.pulsar.common.util.ObjectMapperFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.ZooDefs;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.mockito.Mockito;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

@Test
public class NamespacesTest extends MockedPulsarServiceBaseTest {

    private Namespaces namespaces;

    private List<NamespaceName> testLocalNamespaces;
    private List<NamespaceName> testGlobalNamespaces;
    private final String testTenant = "my-tenant";
    private final String testOtherTenant = "other-tenant";
    private final String testLocalCluster = "use";
    private final String testOtherCluster = "usc";

    protected NamespaceService nsSvc;
    protected Field uriField;
    protected UriInfo uriInfo;

    public NamespacesTest() {
        super();
        conf.setClusterName(testLocalCluster);
    }

    @BeforeClass
    public void initNamespace() throws Exception {
        testLocalNamespaces = Lists.newArrayList();
        testGlobalNamespaces = Lists.newArrayList();

        testLocalNamespaces.add(NamespaceName.get(this.testTenant, this.testLocalCluster, "test-namespace-1"));
        testLocalNamespaces.add(NamespaceName.get(this.testTenant, this.testLocalCluster, "test-namespace-2"));
        testLocalNamespaces.add(NamespaceName.get(this.testTenant, this.testOtherCluster, "test-other-namespace-1"));
        testLocalNamespaces.add(NamespaceName.get(this.testOtherTenant, this.testLocalCluster, "test-namespace-1"));

        testGlobalNamespaces.add(NamespaceName.get(this.testTenant, "global", "test-global-ns1"));

        uriField = PulsarWebResource.class.getDeclaredField("uri");
        uriField.setAccessible(true);
        uriInfo = mock(UriInfo.class);
    }

    @Override
    @BeforeMethod
    public void setup() throws Exception {
        super.internalSetup();

        namespaces = spy(new Namespaces());
        namespaces.setServletContext(new MockServletContext());
        namespaces.setPulsar(pulsar);
        doReturn(mockZookKeeper).when(namespaces).globalZk();
        doReturn(mockZookKeeper).when(namespaces).localZk();
        doReturn(pulsar.getConfigurationCache().propertiesCache()).when(namespaces).tenantsCache();
        doReturn(pulsar.getConfigurationCache().policiesCache()).when(namespaces).policiesCache();
        doReturn(false).when(namespaces).isRequestHttps();
        doReturn("test").when(namespaces).clientAppId();
        doReturn(null).when(namespaces).originalPrincipal();
        doReturn(Sets.newTreeSet(Lists.newArrayList("use", "usw", "usc", "global"))).when(namespaces).clusters();
        doNothing().when(namespaces).validateAdminAccessForTenant(this.testTenant);
        doNothing().when(namespaces).validateAdminAccessForTenant("non-existing-tenant");
        doNothing().when(namespaces).validateAdminAccessForTenant("new-property");

        admin.clusters().createCluster("use", new ClusterData("http://broker-use.com:" + BROKER_WEBSERVICE_PORT));
        admin.clusters().createCluster("usw", new ClusterData("http://broker-usw.com:" + BROKER_WEBSERVICE_PORT));
        admin.clusters().createCluster("usc", new ClusterData("http://broker-usc.com:" + BROKER_WEBSERVICE_PORT));
        admin.tenants().createTenant(this.testTenant,
                new TenantInfo(Sets.newHashSet("role1", "role2"), Sets.newHashSet("use", "usc", "usw")));
        admin.tenants().createTenant(this.testOtherTenant,
                new TenantInfo(Sets.newHashSet("role3", "role4"), Sets.newHashSet("use", "usc", "usw")));

        createTestNamespaces(this.testLocalNamespaces, new BundlesData());
        createGlobalTestNamespaces(this.testTenant, this.testGlobalNamespaces.get(0).getLocalName(),
                new BundlesData());

        doThrow(new RestException(Status.UNAUTHORIZED, "unauthorized")).when(namespaces)
                .validateAdminAccessForTenant(this.testOtherTenant);

        nsSvc = pulsar.getNamespaceService();
    }

    @Override
    @AfterMethod
    public void cleanup() throws Exception {
        super.internalCleanup();
    }

    @Test
    public void testCreateNamespaces() throws Exception {
        try {
            namespaces.createNamespace(this.testTenant, "other-colo", "my-namespace", new BundlesData());
            fail("should have failed");
        } catch (RestException e) {
            // Ok, cluster doesn't exist
        }

        List<NamespaceName> nsnames = Lists.newArrayList();
        nsnames.add(NamespaceName.get(this.testTenant, "use", "create-namespace-1"));
        nsnames.add(NamespaceName.get(this.testTenant, "use", "create-namespace-2"));
        nsnames.add(NamespaceName.get(this.testTenant, "usc", "create-other-namespace-1"));
        createTestNamespaces(nsnames, new BundlesData());

        try {
            namespaces.createNamespace(this.testTenant, "use", "create-namespace-1", new BundlesData());
            fail("should have failed");
        } catch (RestException e) {
            // Ok, namespace already exists
        }

        try {
            namespaces.createNamespace("non-existing-tenant", "use", "create-namespace-1", new BundlesData());
            fail("should have failed");
        } catch (RestException e) {
            // Ok, tenant doesn't exist
        }

        try {
            namespaces.createNamespace(this.testTenant, "use", "create-namespace-#", new BundlesData());
            fail("should have failed");
        } catch (RestException e) {
            // Ok, invalid namespace name
            assertEquals(e.getResponse().getStatus(), Status.PRECONDITION_FAILED.getStatusCode());
        }

        mockZookKeeper.failNow(Code.SESSIONEXPIRED);
        try {
            namespaces.createNamespace(this.testTenant, "use", "my-namespace-3", new BundlesData());
            fail("should have failed");
        } catch (RestException e) {
            // Ok
        }
    }

    @Test
    public void testGetNamespaces() throws Exception {
        List<String> expectedList = Lists.newArrayList(this.testLocalNamespaces.get(0).toString(),
                this.testLocalNamespaces.get(1).toString());
        expectedList.sort(null);
        assertEquals(namespaces.getNamespacesForCluster(this.testTenant, this.testLocalCluster), expectedList);
        expectedList = Lists.newArrayList(this.testLocalNamespaces.get(0).toString(),
                this.testLocalNamespaces.get(1).toString(), this.testLocalNamespaces.get(2).toString(),
                this.testGlobalNamespaces.get(0).toString());
        expectedList.sort(null);
        assertEquals(namespaces.getTenantNamespaces(this.testTenant), expectedList);

        try {
            namespaces.getTenantNamespaces("non-existing-tenant");
            fail("should have failed");
        } catch (RestException e) {
            // Ok, does not exist
        }

        try {
            namespaces.getNamespacesForCluster(this.testTenant, "other-cluster");
            fail("should have failed");
        } catch (RestException e) {
            // Ok, does not exist
        }

        // ZK Errors
        mockZookKeeper.failNow(Code.SESSIONEXPIRED);
        try {
            namespaces.getTenantNamespaces(this.testTenant);
            fail("should have failed");
        } catch (RestException e) {
            // Ok
        }

        mockZookKeeper.failNow(Code.SESSIONEXPIRED);
        try {
            namespaces.getNamespacesForCluster(this.testTenant, this.testLocalCluster);
            fail("should have failed");
        } catch (RestException e) {
            // Ok
        }

    }

    @Test(enabled = false)
    public void testGrantAndRevokePermissions() throws Exception {
        Policies expectedPolicies = new Policies();
        assertEquals(namespaces.getPolicies(this.testTenant, this.testLocalCluster,
                this.testLocalNamespaces.get(0).getLocalName()), expectedPolicies);
        assertEquals(namespaces.getPermissions(this.testTenant, this.testLocalCluster,
                this.testLocalNamespaces.get(0).getLocalName()), expectedPolicies.auth_policies.namespace_auth);

        namespaces.grantPermissionOnNamespace(this.testTenant, this.testLocalCluster,
                this.testLocalNamespaces.get(0).getLocalName(), "my-role", EnumSet.of(AuthAction.produce));

        expectedPolicies.auth_policies.namespace_auth.put("my-role", EnumSet.of(AuthAction.produce));
        assertEquals(namespaces.getPolicies(this.testTenant, this.testLocalCluster,
                this.testLocalNamespaces.get(0).getLocalName()), expectedPolicies);
        assertEquals(namespaces.getPermissions(this.testTenant, this.testLocalCluster,
                this.testLocalNamespaces.get(0).getLocalName()), expectedPolicies.auth_policies.namespace_auth);

        namespaces.grantPermissionOnNamespace(this.testTenant, this.testLocalCluster,
                this.testLocalNamespaces.get(0).getLocalName(), "other-role", EnumSet.of(AuthAction.consume));
        expectedPolicies.auth_policies.namespace_auth.put("other-role", EnumSet.of(AuthAction.consume));
        assertEquals(namespaces.getPolicies(this.testTenant, this.testLocalCluster,
                this.testLocalNamespaces.get(0).getLocalName()), expectedPolicies);
        assertEquals(namespaces.getPermissions(this.testTenant, this.testLocalCluster,
                this.testLocalNamespaces.get(0).getLocalName()), expectedPolicies.auth_policies.namespace_auth);

        namespaces.revokePermissionsOnNamespace(this.testTenant, this.testLocalCluster,
                this.testLocalNamespaces.get(0).getLocalName(), "my-role");
        expectedPolicies.auth_policies.namespace_auth.remove("my-role");
        assertEquals(namespaces.getPolicies(this.testTenant, this.testLocalCluster,
                this.testLocalNamespaces.get(0).getLocalName()), expectedPolicies);
        assertEquals(namespaces.getPermissions(this.testTenant, this.testLocalCluster,
                this.testLocalNamespaces.get(0).getLocalName()), expectedPolicies.auth_policies.namespace_auth);

        // Non-existing namespaces
        try {
            namespaces.getPolicies(this.testTenant, this.testLocalCluster, "non-existing-namespace-1");
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.NOT_FOUND.getStatusCode());
        }

        try {
            namespaces.getPermissions(this.testTenant, this.testLocalCluster, "non-existing-namespace-1");
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.NOT_FOUND.getStatusCode());
        }

        try {
            namespaces.grantPermissionOnNamespace(this.testTenant, this.testLocalCluster, "non-existing-namespace-1",
                    "my-role", EnumSet.of(AuthAction.produce));
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.NOT_FOUND.getStatusCode());
        }

        try {
            namespaces.revokePermissionsOnNamespace(this.testTenant, this.testLocalCluster,
                    "non-existing-namespace-1", "my-role");
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.NOT_FOUND.getStatusCode());
        }

        NamespaceName testNs = this.testLocalNamespaces.get(1);

        mockZookKeeper.failNow(Code.SESSIONEXPIRED);
        try {
            namespaces.getPolicies(testNs.getTenant(), testNs.getCluster(), testNs.getLocalName());
            fail("should have failed");
        } catch (RestException e) {
            // Ok
        }

        mockZookKeeper.failNow(Code.SESSIONEXPIRED);
        try {
            namespaces.getPermissions(testNs.getTenant(), testNs.getCluster(), testNs.getLocalName());
            fail("should have failed");
        } catch (RestException e) {
            // Ok
        }

        mockZookKeeper.failNow(Code.SESSIONEXPIRED);
        try {
            namespaces.grantPermissionOnNamespace(testNs.getTenant(), testNs.getCluster(), testNs.getLocalName(),
                    "other-role", EnumSet.of(AuthAction.consume));
            fail("should have failed");
        } catch (RestException e) {
            // Ok
        }

        mockZookKeeper.failNow(Code.BADVERSION);
        try {
            namespaces.grantPermissionOnNamespace(testNs.getTenant(), testNs.getCluster(), testNs.getLocalName(),
                    "other-role", EnumSet.of(AuthAction.consume));
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.CONFLICT.getStatusCode());
        }

        mockZookKeeper.failNow(Code.BADVERSION);
        try {
            namespaces.revokePermissionsOnNamespace(testNs.getTenant(), testNs.getCluster(), testNs.getLocalName(),
                    "other-role");
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.CONFLICT.getStatusCode());
        }

        mockZookKeeper.failNow(Code.SESSIONEXPIRED);
        try {
            namespaces.revokePermissionsOnNamespace(testNs.getTenant(), testNs.getCluster(), testNs.getLocalName(),
                    "other-role");
            fail("should have failed");
        } catch (RestException e) {
            // Ok
        }
    }

    @Test
    public void testGlobalNamespaceReplicationConfiguration() throws Exception {
        assertEquals(
                namespaces.getNamespaceReplicationClusters(this.testGlobalNamespaces.get(0).getTenant(),
                        this.testGlobalNamespaces.get(0).getCluster(), this.testGlobalNamespaces.get(0).getLocalName()),
                Sets.newHashSet());

        namespaces.setNamespaceReplicationClusters(this.testGlobalNamespaces.get(0).getTenant(),
                this.testGlobalNamespaces.get(0).getCluster(), this.testGlobalNamespaces.get(0).getLocalName(),
                Lists.newArrayList("use", "usw"));
        assertEquals(
                namespaces.getNamespaceReplicationClusters(this.testGlobalNamespaces.get(0).getTenant(),
                        this.testGlobalNamespaces.get(0).getCluster(), this.testGlobalNamespaces.get(0).getLocalName()),
                Lists.newArrayList("use", "usw"));

        try {
            namespaces.setNamespaceReplicationClusters(this.testGlobalNamespaces.get(0).getTenant(),
                    this.testGlobalNamespaces.get(0).getCluster(), this.testGlobalNamespaces.get(0).getLocalName(),
                    Lists.newArrayList("use", "invalid-cluster"));
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.FORBIDDEN.getStatusCode());
        }

        try {
            namespaces.setNamespaceReplicationClusters(this.testGlobalNamespaces.get(0).getTenant(),
                    this.testGlobalNamespaces.get(0).getCluster(), this.testGlobalNamespaces.get(0).getLocalName(),
                    Lists.newArrayList("use", "global"));
            fail("should have failed");
        } catch (RestException e) {
            // Ok, global should not be allowed in the list of replication clusters
            assertEquals(e.getResponse().getStatus(), Status.PRECONDITION_FAILED.getStatusCode());
        }

        try {
            namespaces.setNamespaceReplicationClusters(this.testTenant, "global",
                    this.testGlobalNamespaces.get(0).getLocalName(), Lists.newArrayList("use", "invalid-cluster"));
            fail("should have failed");
        } catch (RestException e) {
            // Ok, invalid-cluster is an invalid cluster id
            assertEquals(e.getResponse().getStatus(), Status.FORBIDDEN.getStatusCode());
        }

        admin.tenants().updateTenant(testTenant,
                new TenantInfo(Sets.newHashSet("role1", "role2"), Sets.newHashSet("use", "usc")));

        try {
            namespaces.setNamespaceReplicationClusters(this.testTenant, "global",
                    this.testGlobalNamespaces.get(0).getLocalName(), Lists.newArrayList("use", "usw"));
            fail("should have failed");
        } catch (RestException e) {
            // Ok, usw was not configured in the list of allowed clusters
            assertEquals(e.getResponse().getStatus(), Status.FORBIDDEN.getStatusCode());
        }

        // Sometimes watcher event consumes scheduled exception, so set to always fail to ensure exception is
        // thrown for api call.
        mockZookKeeper.setAlwaysFail(Code.SESSIONEXPIRED);
        pulsar.getConfigurationCache().policiesCache().invalidate(AdminResource.path(POLICIES, this.testTenant,
                "global", this.testGlobalNamespaces.get(0).getLocalName()));
        try {
            namespaces.setNamespaceReplicationClusters(this.testTenant, "global",
                    this.testGlobalNamespaces.get(0).getLocalName(), Lists.newArrayList("use"));
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.INTERNAL_SERVER_ERROR.getStatusCode());
        } finally {
            mockZookKeeper.unsetAlwaysFail();
        }

        mockZookKeeper.failNow(Code.BADVERSION);
        try {
            namespaces.setNamespaceReplicationClusters(this.testTenant, "global",
                    this.testGlobalNamespaces.get(0).getLocalName(), Lists.newArrayList("use"));
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.CONFLICT.getStatusCode());
        }

        try {
            namespaces.getNamespaceReplicationClusters(this.testTenant, "global", "non-existing-ns");
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.NOT_FOUND.getStatusCode());
        }

        try {
            namespaces.setNamespaceReplicationClusters(this.testTenant, "global", "non-existing-ns",
                    Lists.newArrayList("use"));
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.NOT_FOUND.getStatusCode());
        }

        mockZookKeeper.failNow(Code.SESSIONEXPIRED);
        pulsar.getConfigurationCache().policiesCache().clear();

        // ensure the ZooKeeper read happens, bypassing the cache
        try {
            namespaces.getNamespaceReplicationClusters(this.testTenant, "global",
                    this.testGlobalNamespaces.get(0).getLocalName());
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), 500);
        }

        try {
            namespaces.getNamespaceReplicationClusters(this.testTenant, this.testLocalCluster,
                    this.testLocalNamespaces.get(0).getLocalName());
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.PRECONDITION_FAILED.getStatusCode());
        }

        try {
            namespaces.setNamespaceReplicationClusters(this.testTenant, this.testLocalCluster,
                    this.testLocalNamespaces.get(0).getLocalName(), Lists.newArrayList("use"));
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.PRECONDITION_FAILED.getStatusCode());
        }

    }

    @Test
    public void testGetBundles() throws Exception {
        BundlesData bundle = new BundlesData(Lists.newArrayList("0x00000000", "0x80000000", "0xffffffff"));
        createBundledTestNamespaces(this.testTenant, this.testLocalCluster, "test-bundled-namespace-1", bundle);
        BundlesData responseData = namespaces.getBundlesData(testTenant, this.testLocalCluster,
                "test-bundled-namespace-1");

        assertEquals(responseData, bundle);
    }

    @Test
    public void testNamespacesApiRedirects() throws Exception {
        // Redirect cases
        uriField.set(namespaces, uriInfo);
        doReturn(false).when(namespaces).isLeaderBroker();

        URI uri = URI.create("http://localhost" + ":" + BROKER_WEBSERVICE_PORT + "/admin/namespace/"
                + this.testLocalNamespaces.get(2).toString());
        doReturn(uri).when(uriInfo).getRequestUri();

        // Trick to force redirection
        conf.setAuthorizationEnabled(true);

        try {
            namespaces.deleteNamespace(this.testTenant, this.testOtherCluster,
                    this.testLocalNamespaces.get(2).getLocalName(), false);
            fail("Should have raised exception to redirect request");
        } catch (WebApplicationException wae) {
            // OK
            assertEquals(wae.getResponse().getStatus(), Status.TEMPORARY_REDIRECT.getStatusCode());
            assertEquals(wae.getResponse().getLocation().toString(),
                    UriBuilder.fromUri(uri).host("broker-usc.com").port(BROKER_WEBSERVICE_PORT).toString());
        }

        uri = URI.create("http://localhost" + ":" + BROKER_WEBSERVICE_PORT + "/admin/namespace/"
                + this.testLocalNamespaces.get(2).toString() + "/unload");
        doReturn(uri).when(uriInfo).getRequestUri();

        try {
            namespaces.unloadNamespaceBundle(this.testTenant, this.testOtherCluster,
                    this.testLocalNamespaces.get(2).getLocalName(), "0x00000000_0xffffffff", false);
            fail("Should have raised exception to redirect request");
        } catch (WebApplicationException wae) {
            // OK
            assertEquals(wae.getResponse().getStatus(), Status.TEMPORARY_REDIRECT.getStatusCode());
            assertEquals(wae.getResponse().getLocation().toString(),
                    UriBuilder.fromUri(uri).host("broker-usc.com").port(BROKER_WEBSERVICE_PORT).toString());
        }

        uri = URI.create("http://localhost" + ":" + BROKER_WEBSERVICE_PORT + "/admin/namespace/"
                + this.testGlobalNamespaces.get(0).toString() + "/configversion");
        doReturn(uri).when(uriInfo).getRequestUri();

        // setup to redirect to another broker in the same cluster
        doReturn(Optional.of(new URL("http://otherhost" + ":" + BROKER_WEBSERVICE_PORT))).when(nsSvc)
                .getWebServiceUrl(Mockito.argThat(new Matcher<NamespaceName>() {

                    @Override
                    public void describeTo(Description description) {
                        // TODO Auto-generated method stub

                    }

                    @Override
                    public boolean matches(Object item) {
                        NamespaceName nsname = (NamespaceName) item;
                        return nsname.equals(NamespacesTest.this.testGlobalNamespaces.get(0));
                    }

                    @Override
                    public void _dont_implement_Matcher___instead_extend_BaseMatcher_() {
                        // TODO Auto-generated method stub

                    }

                }), Mockito.anyBoolean(), Mockito.anyBoolean(), Mockito.anyBoolean());

        admin.namespaces().setNamespaceReplicationClusters(testGlobalNamespaces.get(0).toString(),
                Sets.newHashSet("usw"));

        uri = URI.create("http://localhost" + ":" + BROKER_WEBSERVICE_PORT + "/admin/namespace/"
                + this.testLocalNamespaces.get(2).toString() + "?authoritative=false");
        doReturn(uri).when(uriInfo).getRequestUri();
        doReturn(true).when(namespaces).isLeaderBroker();

        try {
            namespaces.deleteNamespace(this.testLocalNamespaces.get(2).getTenant(),
                    this.testLocalNamespaces.get(2).getCluster(), this.testLocalNamespaces.get(2).getLocalName(),
                    false);
            fail("Should have raised exception to redirect request");
        } catch (WebApplicationException wae) {
            // OK
            assertEquals(wae.getResponse().getStatus(), Status.TEMPORARY_REDIRECT.getStatusCode());
            assertEquals(wae.getResponse().getLocation().toString(),
                    UriBuilder.fromUri(uri).host("broker-usc.com").port(BROKER_WEBSERVICE_PORT).toString());
        }
    }

    @Test
    public void testDeleteNamespaces() throws Exception {
        try {
            namespaces.deleteNamespace(this.testTenant, this.testLocalCluster, "non-existing-namespace-1", false);
            fail("should have failed");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.NOT_FOUND.getStatusCode());
        }

        NamespaceName testNs = this.testLocalNamespaces.get(1);
        TopicName topicName = TopicName.get(testNs.getPersistentTopicName("my-topic"));
        ZkUtils.createFullPathOptimistic(mockZookKeeper, "/managed-ledgers/" + topicName.getPersistenceNamingEncoding(),
                new byte[0], null, null);

        // setup ownership to localhost
        URL localWebServiceUrl = new URL(pulsar.getWebServiceAddress());
        doReturn(Optional.of(localWebServiceUrl)).when(nsSvc).getWebServiceUrl(testNs, false, false, false);
        doReturn(true).when(nsSvc).isServiceUnitOwned(testNs);
        try {
            namespaces.deleteNamespace(testNs.getTenant(), testNs.getCluster(), testNs.getLocalName(), false);
            fail("should have failed");
        } catch (RestException e) {
            // Ok, namespace not empty
            assertEquals(e.getResponse().getStatus(), Status.CONFLICT.getStatusCode());
        }
        // delete the topic from ZK
        mockZookKeeper.delete("/managed-ledgers/" + topicName.getPersistenceNamingEncoding(), -1);

        ZkUtils.createFullPathOptimistic(mockZookKeeper, "/admin/partitioned-topics/" + topicName.getPersistenceNamingEncoding(),
                new byte[0], null, null);
        try {
            namespaces.deleteNamespace(testNs.getTenant(), testNs.getCluster(), testNs.getLocalName(), false);
            fail("should have failed");
        } catch (RestException e) {
            // Ok, namespace not empty
            assertEquals(e.getResponse().getStatus(), Status.CONFLICT.getStatusCode());
        }
        mockZookKeeper.delete("/admin/partitioned-topics/" + topicName.getPersistenceNamingEncoding(), -1);

        testNs = this.testGlobalNamespaces.get(0);
        // setup ownership to localhost
        doReturn(Optional.of(localWebServiceUrl)).when(nsSvc).getWebServiceUrl(testNs, false, false, false);
        doReturn(true).when(nsSvc).isServiceUnitOwned(testNs);
        namespaces.deleteNamespace(testNs.getTenant(), testNs.getCluster(), testNs.getLocalName(), false);

        testNs = this.testLocalNamespaces.get(0);
        // setup ownership to localhost
        doReturn(Optional.of(localWebServiceUrl)).when(nsSvc).getWebServiceUrl(testNs, false, false, false);
        doReturn(true).when(nsSvc).isServiceUnitOwned(testNs);
        namespaces.deleteNamespace(testNs.getTenant(), testNs.getCluster(), testNs.getLocalName(), false);
        List<String> nsList = Lists.newArrayList(this.testLocalNamespaces.get(1).toString(),
                this.testLocalNamespaces.get(2).toString());
        nsList.sort(null);
        assertEquals(namespaces.getTenantNamespaces(this.testTenant), nsList);

        testNs = this.testLocalNamespaces.get(1);
        // ensure refreshed topics list in the cache
        pulsar.getLocalZkCacheService().managedLedgerListCache().clearTree();
        // setup ownership to localhost
        doReturn(Optional.of(localWebServiceUrl)).when(nsSvc).getWebServiceUrl(testNs, false, false, false);
        doReturn(true).when(nsSvc).isServiceUnitOwned(testNs);
        namespaces.deleteNamespace(testNs.getTenant(), testNs.getCluster(), testNs.getLocalName(), false);
    }

    @Test
    public void testDeleteNamespaceWithBundles() throws Exception {
        URL localWebServiceUrl = new URL(pulsar.getWebServiceAddress());
        String bundledNsLocal = "test-bundled-namespace-1";
        BundlesData bundleData = new BundlesData(Lists.newArrayList("0x00000000", "0x80000000", "0xffffffff"));
        createBundledTestNamespaces(this.testTenant, this.testLocalCluster, bundledNsLocal, bundleData);
        final NamespaceName testNs = NamespaceName.get(this.testTenant, this.testLocalCluster, bundledNsLocal);

        org.apache.pulsar.client.admin.Namespaces namespacesAdmin = mock(org.apache.pulsar.client.admin.Namespaces.class);
        doReturn(namespacesAdmin).when(admin).namespaces();

        doReturn(null).when(nsSvc).getWebServiceUrl(Mockito.argThat(new Matcher<NamespaceBundle>() {

            @Override
            public void describeTo(Description description) {
            }

            @Override
            public boolean matches(Object item) {
                if (item instanceof NamespaceBundle) {
                    NamespaceBundle bundle = (NamespaceBundle) item;
                    return bundle.getNamespaceObject().equals(testNs);
                }
                return false;
            }

            @Override
            public void _dont_implement_Matcher___instead_extend_BaseMatcher_() {
            }

        }), Mockito.anyBoolean(), Mockito.anyBoolean(), Mockito.anyBoolean());
        doReturn(false).when(nsSvc).isServiceUnitOwned(Mockito.argThat(new Matcher<NamespaceBundle>() {

            @Override
            public void describeTo(Description description) {
            }

            @Override
            public boolean matches(Object item) {
                if (item instanceof NamespaceBundle) {
                    NamespaceBundle bundle = (NamespaceBundle) item;
                    return bundle.getNamespaceObject().equals(testNs);
                }
                return false;
            }

            @Override
            public void _dont_implement_Matcher___instead_extend_BaseMatcher_() {
            }

        }));
        doReturn(Optional.of(new NamespaceEphemeralData())).when(nsSvc)
                .getOwner(Mockito.argThat(new Matcher<NamespaceBundle>() {

                    @Override
                    public void describeTo(Description description) {
                    }

                    @Override
                    public boolean matches(Object item) {
                        if (item instanceof NamespaceBundle) {
                            NamespaceBundle bundle = (NamespaceBundle) item;
                            return bundle.getNamespaceObject().equals(testNs);
                        }
                        return false;
                    }

                    @Override
                    public void _dont_implement_Matcher___instead_extend_BaseMatcher_() {
                    }
                }));

        doThrow(new PulsarAdminException.PreconditionFailedException(
                new ClientErrorException(Status.PRECONDITION_FAILED))).when(namespacesAdmin)
                        .deleteNamespaceBundle(Mockito.anyString(), Mockito.anyString());

        try {
            namespaces.deleteNamespaceBundle(testTenant, testLocalCluster, bundledNsLocal, "0x00000000_0x80000000",
                    false);
            fail("Should have failed");
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatus(), Status.PRECONDITION_FAILED.getStatusCode());
        }

        try {
            namespaces.deleteNamespace(testTenant, testLocalCluster, bundledNsLocal, false);
            fail("Should have failed");
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatus(), Status.PRECONDITION_FAILED.getStatusCode());
        }

        NamespaceBundles nsBundles = nsSvc.getNamespaceBundleFactory().getBundles(testNs, bundleData);
        // make one bundle owned
        doReturn(Optional.of(localWebServiceUrl)).when(nsSvc).getWebServiceUrl(nsBundles.getBundles().get(0), false, true, false);
        doReturn(true).when(nsSvc).isServiceUnitOwned(nsBundles.getBundles().get(0));
        doNothing().when(namespacesAdmin).deleteNamespaceBundle(
                testTenant + "/" + testLocalCluster + "/" + bundledNsLocal, "0x00000000_0x80000000");

        try {
            namespaces.deleteNamespaceBundle(testTenant, testLocalCluster, bundledNsLocal, "0x80000000_0xffffffff",
                    false);
            fail("Should have failed");
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatus(), Status.PRECONDITION_FAILED.getStatusCode());
        }

        try {
            namespaces.deleteNamespace(testTenant, testLocalCluster, bundledNsLocal, false);
            fail("should have failed");
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatus(), Status.PRECONDITION_FAILED.getStatusCode());
        }

        // ensure all three bundles are owned by the local broker
        for (NamespaceBundle bundle : nsBundles.getBundles()) {
            doReturn(Optional.of(localWebServiceUrl)).when(nsSvc).getWebServiceUrl(bundle, false, true, false);
            doReturn(true).when(nsSvc).isServiceUnitOwned(bundle);
        }
        doNothing().when(namespacesAdmin).deleteNamespaceBundle(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    public void testUnloadNamespaces() throws Exception {
        final NamespaceName testNs = this.testLocalNamespaces.get(1);
        URL localWebServiceUrl = new URL(pulsar.getWebServiceAddress());
        doReturn(Optional.of(localWebServiceUrl)).when(nsSvc)
                .getWebServiceUrl(Mockito.argThat(new Matcher<NamespaceBundle>() {

                    @Override
                    public void describeTo(Description description) {
                        // TODO Auto-generated method stub

                    }

                    @Override
                    public boolean matches(Object item) {
                        if (item instanceof NamespaceName) {
                            NamespaceName ns = (NamespaceName) item;
                            return ns.equals(testNs);
                        }
                        return false;
                    }

                    @Override
                    public void _dont_implement_Matcher___instead_extend_BaseMatcher_() {
                        // TODO Auto-generated method stub

                    }

                }), Mockito.anyBoolean(), Mockito.anyBoolean(), Mockito.anyBoolean());
        doReturn(true).when(nsSvc).isServiceUnitOwned(Mockito.argThat(new Matcher<NamespaceBundle>() {

            @Override
            public void describeTo(Description description) {
                // TODO Auto-generated method stub

            }

            @Override
            public boolean matches(Object item) {
                if (item instanceof NamespaceName) {
                    NamespaceName ns = (NamespaceName) item;
                    return ns.equals(testNs);
                }
                return false;
            }

            @Override
            public void _dont_implement_Matcher___instead_extend_BaseMatcher_() {
                // TODO Auto-generated method stub

            }

        }));

        NamespaceBundle bundle = nsSvc.getNamespaceBundleFactory().getFullBundle(testNs);
        doNothing().when(namespaces).validateBundleOwnership(bundle, false, true);

        // The namespace unload should succeed on all the bundles
        namespaces.unloadNamespace(testNs.getTenant(), testNs.getCluster(), testNs.getLocalName());
    }

    @Test
    public void testSplitBundles() throws Exception {
        URL localWebServiceUrl = new URL(pulsar.getWebServiceAddress());
        String bundledNsLocal = "test-bundled-namespace-1";
        BundlesData bundleData = new BundlesData(Lists.newArrayList("0x00000000", "0xffffffff"));
        createBundledTestNamespaces(this.testTenant, this.testLocalCluster, bundledNsLocal, bundleData);
        final NamespaceName testNs = NamespaceName.get(this.testTenant, this.testLocalCluster, bundledNsLocal);

        OwnershipCache MockOwnershipCache = spy(pulsar.getNamespaceService().getOwnershipCache());
        doNothing().when(MockOwnershipCache).disableOwnership(any(NamespaceBundle.class));
        Field ownership = NamespaceService.class.getDeclaredField("ownershipCache");
        ownership.setAccessible(true);
        ownership.set(pulsar.getNamespaceService(), MockOwnershipCache);
        mockWebUrl(localWebServiceUrl, testNs);

        // split bundles
        try {
            namespaces.splitNamespaceBundle(testTenant, testLocalCluster, bundledNsLocal, "0x00000000_0xffffffff",
                    false, true);
            // verify split bundles
            BundlesData bundlesData = namespaces.getBundlesData(testTenant, testLocalCluster, bundledNsLocal);
            assertNotNull(bundlesData);
            assertTrue(bundlesData.boundaries.size() == 3);
            assertTrue(bundlesData.boundaries.get(0).equals("0x00000000"));
            assertTrue(bundlesData.boundaries.get(1).equals("0x7fffffff"));
            assertTrue(bundlesData.boundaries.get(2).equals("0xffffffff"));
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatus(), Status.PRECONDITION_FAILED.getStatusCode());
        }
    }

    @Test
    public void testSplitBundleWithUnDividedRange() throws Exception {
        URL localWebServiceUrl = new URL(pulsar.getWebServiceAddress());
        String bundledNsLocal = "test-bundled-namespace-1";
        BundlesData bundleData = new BundlesData(
                Lists.newArrayList("0x00000000", "0x08375b1a", "0x08375b1b", "0xffffffff"));
        createBundledTestNamespaces(this.testTenant, this.testLocalCluster, bundledNsLocal, bundleData);
        final NamespaceName testNs = NamespaceName.get(this.testTenant, this.testLocalCluster, bundledNsLocal);

        OwnershipCache MockOwnershipCache = spy(pulsar.getNamespaceService().getOwnershipCache());
        doNothing().when(MockOwnershipCache).disableOwnership(any(NamespaceBundle.class));
        Field ownership = NamespaceService.class.getDeclaredField("ownershipCache");
        ownership.setAccessible(true);
        ownership.set(pulsar.getNamespaceService(), MockOwnershipCache);
        mockWebUrl(localWebServiceUrl, testNs);

        // split bundles
        try {
            namespaces.splitNamespaceBundle(testTenant, testLocalCluster, bundledNsLocal, "0x08375b1a_0x08375b1b",
                    false, false);
        } catch (RestException re) {
            assertEquals(re.getResponse().getStatus(), Status.PRECONDITION_FAILED.getStatusCode());
        }
    }

    @Test
    public void testUnloadNamespaceWithBundles() throws Exception {
        URL localWebServiceUrl = new URL(pulsar.getWebServiceAddress());
        String bundledNsLocal = "test-bundled-namespace-1";
        BundlesData bundleData = new BundlesData(Lists.newArrayList("0x00000000", "0x80000000", "0xffffffff"));
        createBundledTestNamespaces(this.testTenant, this.testLocalCluster, bundledNsLocal, bundleData);
        final NamespaceName testNs = NamespaceName.get(this.testTenant, this.testLocalCluster, bundledNsLocal);

        doReturn(Optional.of(localWebServiceUrl)).when(nsSvc)
                .getWebServiceUrl(Mockito.argThat(new Matcher<NamespaceBundle>() {

                    @Override
                    public void describeTo(Description description) {
                        // TODO Auto-generated method stub

                    }

                    @Override
                    public boolean matches(Object item) {
                        if (item instanceof NamespaceBundle) {
                            NamespaceBundle bundle = (NamespaceBundle) item;
                            return bundle.getNamespaceObject().equals(testNs);
                        }
                        return false;
                    }

                    @Override
                    public void _dont_implement_Matcher___instead_extend_BaseMatcher_() {
                        // TODO Auto-generated method stub

                    }

                }), Mockito.anyBoolean(), Mockito.anyBoolean(), Mockito.anyBoolean());
        doReturn(true).when(nsSvc).isServiceUnitOwned(Mockito.argThat(new Matcher<NamespaceBundle>() {

            @Override
            public void describeTo(Description description) {
                // TODO Auto-generated method stub

            }

            @Override
            public boolean matches(Object item) {
                if (item instanceof NamespaceBundle) {
                    NamespaceBundle bundle = (NamespaceBundle) item;
                    return bundle.getNamespaceObject().equals(testNs);
                }
                return false;
            }

            @Override
            public void _dont_implement_Matcher___instead_extend_BaseMatcher_() {
                // TODO Auto-generated method stub

            }

        }));

        NamespaceBundles nsBundles = nsSvc.getNamespaceBundleFactory().getBundles(testNs, bundleData);
        NamespaceBundle testBundle = nsBundles.getBundles().get(0);
        // make one bundle owned
        doReturn(Optional.of(localWebServiceUrl)).when(nsSvc).getWebServiceUrl(testBundle, false, true, false);
        doReturn(true).when(nsSvc).isServiceUnitOwned(testBundle);
        doNothing().when(nsSvc).unloadNamespaceBundle(testBundle);
        namespaces.unloadNamespaceBundle(testTenant, testLocalCluster, bundledNsLocal, "0x00000000_0x80000000",
                false);
        verify(nsSvc, times(1)).unloadNamespaceBundle(testBundle);
        try {
            namespaces.unloadNamespaceBundle(testTenant, testLocalCluster, bundledNsLocal, "0x00000000_0x88000000",
                    false);
            fail("should have failed");
        } catch (RestException re) {
            // ok
        }
    }

    private void createBundledTestNamespaces(String property, String cluster, String namespace, BundlesData bundle)
            throws Exception {
        namespaces.createNamespace(property, cluster, namespace, bundle);
    }

    private void createGlobalTestNamespaces(String property, String namespace, BundlesData bundle) throws Exception {
        namespaces.createNamespace(property, "global", namespace, bundle);
    }

    private void createTestNamespaces(List<NamespaceName> nsnames, BundlesData bundle) throws Exception {
        for (NamespaceName nsName : nsnames) {
            namespaces.createNamespace(nsName.getTenant(), nsName.getCluster(), nsName.getLocalName(), bundle);
        }
    }

    @Test
    public void testValidateAdminAccessOnTenant() throws Exception {

        try {
            final String property = "prop";
            pulsar.getConfiguration().setAuthenticationEnabled(true);
            pulsar.getConfiguration().setAuthorizationEnabled(true);
            final String path = PulsarWebResource.path(POLICIES, property);
            final String data = ObjectMapperFactory.getThreadLocal().writeValueAsString(
                    new TenantInfo(Sets.newHashSet(namespaces.clientAppId()), Sets.newHashSet("use")));
            ZkUtils.createFullPathOptimistic(pulsar.getConfigurationCache().getZooKeeper(), path, data.getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            namespaces.validateAdminAccessForTenant(property);
        } finally {
            pulsar.getConfiguration().setAuthenticationEnabled(false);
            pulsar.getConfiguration().setAuthorizationEnabled(false);
        }
    }

    @Test
    public void testValidateNamespaceOwnershipWithBundles() throws Exception {
        try {
            URL localWebServiceUrl = new URL(pulsar.getWebServiceAddress());
            String bundledNsLocal = "test-bundled-namespace-1";
            BundlesData bundleData = new BundlesData(Lists.newArrayList("0x00000000", "0xffffffff"));
            createBundledTestNamespaces(this.testTenant, this.testLocalCluster, bundledNsLocal, bundleData);
            final NamespaceName testNs = NamespaceName.get(this.testTenant, this.testLocalCluster, bundledNsLocal);
            mockWebUrl(localWebServiceUrl, testNs);

            OwnershipCache MockOwnershipCache = spy(pulsar.getNamespaceService().getOwnershipCache());
            doNothing().when(MockOwnershipCache).disableOwnership(any(NamespaceBundle.class));
            Field ownership = NamespaceService.class.getDeclaredField("ownershipCache");
            ownership.setAccessible(true);
            ownership.set(pulsar.getNamespaceService(), MockOwnershipCache);
            namespaces.validateNamespaceOwnershipWithBundles(this.testTenant, this.testLocalCluster, bundledNsLocal,
                    false, true, bundleData);
        } catch (RestException e) {
            fail("ValidateNamespaceOwnershipWithBundles failed");
        }
    }

    @Test
    public void testRetention() throws Exception {
        try {
            URL localWebServiceUrl = new URL(pulsar.getWebServiceAddress());
            String bundledNsLocal = "test-bundled-namespace-1";
            BundlesData bundleData = new BundlesData(Lists.newArrayList("0x00000000", "0xffffffff"));
            createBundledTestNamespaces(this.testTenant, this.testLocalCluster, bundledNsLocal, bundleData);
            final NamespaceName testNs = NamespaceName.get(this.testTenant, this.testLocalCluster, bundledNsLocal);
            mockWebUrl(localWebServiceUrl, testNs);

            OwnershipCache MockOwnershipCache = spy(pulsar.getNamespaceService().getOwnershipCache());
            doNothing().when(MockOwnershipCache).disableOwnership(any(NamespaceBundle.class));
            Field ownership = NamespaceService.class.getDeclaredField("ownershipCache");
            ownership.setAccessible(true);
            ownership.set(pulsar.getNamespaceService(), MockOwnershipCache);
            RetentionPolicies retention = new RetentionPolicies(10, 10);
            namespaces.setRetention(this.testTenant, this.testLocalCluster, bundledNsLocal, retention);
            RetentionPolicies retention2 = namespaces.getRetention(this.testTenant, this.testLocalCluster,
                    bundledNsLocal);
            assertEquals(retention, retention2);
        } catch (RestException e) {
            fail("ValidateNamespaceOwnershipWithBundles failed");
        }
    }

    @Test
    public void testRetentionUnauthorized() throws Exception {
        try {
            NamespaceName testNs = this.testLocalNamespaces.get(3);
            RetentionPolicies retention = new RetentionPolicies(10, 10);
            namespaces.setRetention(testNs.getTenant(), testNs.getCluster(), testNs.getLocalName(), retention);
            fail("Should fail");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.UNAUTHORIZED.getStatusCode());
        }
    }

    @Test
    public void testPersistence() throws Exception {
        NamespaceName testNs = this.testLocalNamespaces.get(0);
        PersistencePolicies persistence1 = new PersistencePolicies(3, 2, 1, 0.0);
        namespaces.setPersistence(testNs.getTenant(), testNs.getCluster(), testNs.getLocalName(), persistence1);
        PersistencePolicies persistence2 = namespaces.getPersistence(testNs.getTenant(), testNs.getCluster(),
                testNs.getLocalName());
        assertEquals(persistence2, persistence1);
    }

    @Test
    public void testPersistenceUnauthorized() throws Exception {
        try {
            NamespaceName testNs = this.testLocalNamespaces.get(3);
            PersistencePolicies persistence = new PersistencePolicies(3, 2, 1, 0.0);
            namespaces.setPersistence(testNs.getTenant(), testNs.getCluster(), testNs.getLocalName(), persistence);
            fail("Should fail");
        } catch (RestException e) {
            assertEquals(e.getResponse().getStatus(), Status.UNAUTHORIZED.getStatusCode());
        }
    }

    @Test
    public void testValidateTopicOwnership() throws Exception {
        URL localWebServiceUrl = new URL(pulsar.getWebServiceAddress());
        String bundledNsLocal = "test-bundled-namespace-1";
        BundlesData bundleData = new BundlesData(Lists.newArrayList("0x00000000", "0xffffffff"));
        createBundledTestNamespaces(this.testTenant, this.testLocalCluster, bundledNsLocal, bundleData);
        final NamespaceName testNs = NamespaceName.get(this.testTenant, this.testLocalCluster, bundledNsLocal);
        OwnershipCache MockOwnershipCache = spy(pulsar.getNamespaceService().getOwnershipCache());
        doNothing().when(MockOwnershipCache).disableOwnership(any(NamespaceBundle.class));
        Field ownership = NamespaceService.class.getDeclaredField("ownershipCache");
        ownership.setAccessible(true);
        ownership.set(pulsar.getNamespaceService(), MockOwnershipCache);
        TopicName topicName = TopicName.get(testNs.getPersistentTopicName("my-topic"));
        PersistentTopics topics = spy(new PersistentTopics());
        topics.setServletContext(new MockServletContext());
        topics.setPulsar(pulsar);
        doReturn(false).when(topics).isRequestHttps();
        doReturn("test").when(topics).clientAppId();
        doReturn(null).when(topics).originalPrincipal();
        mockWebUrl(localWebServiceUrl, testNs);
        doReturn("persistent").when(topics).domain();

        topics.validateTopicName(topicName.getTenant(), topicName.getCluster(),
                                 topicName.getNamespacePortion(), topicName.getEncodedLocalName());
        topics.validateAdminOperationOnTopic(false);
    }

    @Test
    public void testIsLeader() throws Exception {
        assertTrue(namespaces.isLeaderBroker());
    }

    /**
     * Verifies that deleteNamespace cleans up policies(global,local), bundle cache and bundle ownership
     *
     * @throws Exception
     */
    @Test
    public void testDeleteNamespace() throws Exception {

        final String namespace = this.testTenant + "/use/deleteNs";
        admin.namespaces().createNamespace(namespace, 100);
        assertEquals(admin.namespaces().getPolicies(namespace).bundles.numBundles, 100);

        // (1) Force topic creation and namespace being loaded
        final String topicName = "persistent://" + namespace + "/my-topic";
        TopicName topic = TopicName.get(topicName);

        Producer producer = pulsarClient.createProducer(topicName);
        producer.close();
        NamespaceBundle bundle1 = pulsar.getNamespaceService().getBundle(topic);
        // (2) Delete topic
        admin.topics().delete(topicName);
        // (3) Delete ns
        admin.namespaces().deleteNamespace(namespace);
        // (4) check bundle
        NamespaceBundle bundle2 = pulsar.getNamespaceService().getBundle(topic);
        assertNotEquals(bundle1.getBundleRange(), bundle2.getBundleRange());
        // returns full bundle if policies not present
        assertEquals("0x00000000_0xffffffff", bundle2.getBundleRange());

    }

    private void mockWebUrl(URL localWebServiceUrl, NamespaceName namespace) throws Exception {
        doReturn(Optional.of(localWebServiceUrl)).when(nsSvc)
                .getWebServiceUrl(Mockito.argThat(new Matcher<NamespaceBundle>() {
                    @Override
                    public void describeTo(Description description) {
                    }

                    @Override
                    public boolean matches(Object item) {
                        if (item instanceof NamespaceBundle) {
                            NamespaceBundle bundle = (NamespaceBundle) item;
                            return bundle.getNamespaceObject().equals(namespace);
                        }
                        return false;
                    }

                    @Override
                    public void _dont_implement_Matcher___instead_extend_BaseMatcher_() {
                    }
                }), Mockito.anyBoolean(), Mockito.anyBoolean(), Mockito.anyBoolean());
        doReturn(true).when(nsSvc).isServiceUnitOwned(Mockito.argThat(new Matcher<NamespaceBundle>() {
            @Override
            public void describeTo(Description description) {
            }

            @Override
            public boolean matches(Object item) {
                if (item instanceof NamespaceBundle) {
                    NamespaceBundle bundle = (NamespaceBundle) item;
                    return bundle.getNamespaceObject().equals(namespace);
                }
                return false;
            }

            @Override
            public void _dont_implement_Matcher___instead_extend_BaseMatcher_() {
            }
        }));
    }
}
