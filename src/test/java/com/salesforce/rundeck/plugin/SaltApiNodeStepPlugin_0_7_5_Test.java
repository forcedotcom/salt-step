package com.salesforce.rundeck.plugin;

import java.io.IOException;

import junit.framework.Assert;

import org.apache.http.HttpException;
import org.apache.http.HttpStatus;
import org.junit.Before;
import org.junit.Test;

public class SaltApiNodeStepPlugin_0_7_5_Test extends AbstractSaltApiNodeStepPlugin_BackwardsCompatabilityTest {

    @Override
    protected String getVersion() {
        return "0.7.5";
    }

    @Before
    public void setup() {
        spyPlugin();
    }

    @Test
    public void testAuthenticateWithRedirectResponseCode() throws IOException, HttpException {
        setupAuthenticationHeadersOnPost(HttpStatus.SC_MOVED_TEMPORARILY);

        Assert.assertEquals(AUTH_TOKEN, plugin.authenticate(legacyCapability, client, PARAM_USER, PARAM_PASSWORD));

        assertThatAuthenticationAttemptedSuccessfully();
    }
    
    @Test
    public void testAuthenticateFailure() throws IOException, HttpException {
        setupResponseCode(post, HttpStatus.SC_INTERNAL_SERVER_ERROR);

        Assert.assertNull(plugin.authenticate(legacyCapability, client, PARAM_USER, PARAM_PASSWORD));

        assertThatAuthenticationAttemptedSuccessfully();
    }
}