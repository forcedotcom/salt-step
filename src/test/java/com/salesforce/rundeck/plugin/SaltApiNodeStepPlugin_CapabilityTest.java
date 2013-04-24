package com.salesforce.rundeck.plugin;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.salesforce.rundeck.plugin.version.SaltApiCapability;
import com.salesforce.rundeck.plugin.version.SaltApiVersionCapabilityRegistry;

public class SaltApiNodeStepPlugin_CapabilityTest extends AbstractSaltApiNodeStepPluginTest {

    protected SaltApiVersionCapabilityRegistry registry;

    @Before
    public void setup() {
        registry = Mockito.mock(SaltApiVersionCapabilityRegistry.class);
        plugin.capabilityRegistry = registry;
    }

    @Test
    public void testGetCapabilityWithNoVersionSupplied() {
        SaltApiCapability capability = new SaltApiCapability();
        Mockito.when(registry.getLatest()).thenReturn(capability);

        Assert.assertSame(capability, plugin.getSaltApiCapability());
    }

    @Test
    public void testGetCapabilityWithBlankVersionSupplied() {
        plugin.saltApiVersion = "   ";
        SaltApiCapability capability = new SaltApiCapability();
        Mockito.when(registry.getLatest()).thenReturn(capability);

        Assert.assertSame(capability, plugin.getSaltApiCapability());
    }

    @Test
    public void testGetCapabilityWithVersionSupplied() {
        String version = "someversion";
        plugin.saltApiVersion = version;
        SaltApiCapability capability = new SaltApiCapability();
        Mockito.when(registry.getCapability(version)).thenReturn(capability);

        Assert.assertSame(capability, plugin.getSaltApiCapability());
    }
}