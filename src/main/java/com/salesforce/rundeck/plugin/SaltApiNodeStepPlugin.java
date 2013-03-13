package com.salesforce.rundeck.plugin;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;

import com.dtolabs.rundeck.core.Constants;
import com.dtolabs.rundeck.core.common.INodeEntry;
import com.dtolabs.rundeck.core.execution.workflow.steps.FailureReason;
import com.dtolabs.rundeck.core.execution.workflow.steps.node.NodeStepException;
import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.plugins.ServiceNameConstants;
import com.dtolabs.rundeck.plugins.descriptions.PluginDescription;
import com.dtolabs.rundeck.plugins.descriptions.PluginProperty;
import com.dtolabs.rundeck.plugins.step.NodeStepPlugin;
import com.dtolabs.rundeck.plugins.step.PluginStepContext;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * This plugin allows salt execution on a specific minion using the salt-api
 * interface.
 * 
 * Pre-requisites:
 * <ul>
 * <li>Salt-api must be installed.</li>
 * <li>Project node resources must be configured with the name as the salt
 * minion's name as configured on the salt master.</li>
 * <li>SALT_USER and SALT_PASSWORD options must be configured and provided on
 * the job.</li>
 * </ul>
 */
@Plugin(name = SaltApiNodeStepPlugin.SERVICE_PROVIDER_NAME, service = ServiceNameConstants.WorkflowNodeStep)
@PluginDescription(title = "Remote Salt Execution", description = "Run a command on a remote salt master through salt-api.")
public class SaltApiNodeStepPlugin implements NodeStepPlugin {
    public enum SaltApiNodeStepFailureReason implements FailureReason {
        AUTHENTICATION_FAILURE, COMMUNICATION_FAILURE, SALT_API_FAILURE, SALT_TARGET_MISMATCH, INTERRUPTED;
    }

    protected static class HttpFactory {
        public HttpClient createHttpClient() {
            return new HttpClient();
        }

        public PostMethod createPostMethod(String uri) {
            return new PostMethod(uri);
        }

        public GetMethod createGetMethod(String uri) {
            return new GetMethod(uri);
        }
    }

    public static final String SERVICE_PROVIDER_NAME = "salt-api-exec";

    protected static final String LOGIN_RESOURCE = "/login";
    protected static final String MINION_RESOURCE = "/minions";
    protected static final String JOBS_RESOURCE = "/jobs";
    protected static final String SALT_AUTH_TOKEN_HEADER = "X-Auth-Token";
    protected static final String CHAR_SET_ENCODING = "UTF-8";
    protected static final String REQUEST_CONTENT_TYPE = "application/x-www-form-urlencoded";
    protected static final String REQUEST_ACCEPT_HEADER_NAME = "Accept";
    protected static final String JSON_RESPONSE_ACCEPT_TYPE = "application/json";
    protected static final String YAML_RESPONSE_ACCEPT_TYPE = "application/x-yaml";

    protected static final String SALT_OUTPUT_RETURN_KEY = "return";
    protected static final Type MINION_RESPONSE_TYPE = new TypeToken<List<Map<String, Object>>>() {}.getType();
    protected static final Type JOB_RESPONSE_TYPE = new TypeToken<Map<String, List<Object>>>() {}.getType();

    protected static final String SALT_API_FUNCTION_PARAM_NAME = "fun";
    protected static final String SALT_API_ARGUMENTS_PARAM_NAME = "arg";
    protected static final String SALT_API_TARGET_PARAM_NAME = "tgt";
    protected static final String SALT_API_USERNAME_PARAM_NAME = "username";
    protected static final String SALT_API_PASSWORD_PARAM_NAME = "password";
    protected static final String SALT_API_EAUTH_PARAM_NAME = "eauth";

    protected static final String RUNDECK_DATA_CONTEXT_OPTION_KEY = "option";
    protected static final String SALT_USER_PARAM = "SALT_USER";
    protected static final String SALT_PASSWORD_PARAM = "SALT_PASSWORD";

    protected HttpFactory httpFactory = new HttpFactory();
    protected long pollFrequency = 15000L;

    @PluginProperty(title = "SALT_API_END_POINT", description = "Salt Api end point", required = true)
    protected String saltEndpoint;

    @PluginProperty(title = "Function", description = "Function (including args) to invoke on salt minions", required = true)
    protected String function;

    @PluginProperty(title = "EAuth", description = "Salt Master's external authentication system", required = true)
    protected String eAuth;

    @Override
    public void executeNodeStep(PluginStepContext context, Map<String, Object> configuration, INodeEntry entry)
            throws NodeStepException {
        try {
            HttpClient client = httpFactory.createHttpClient();
            Map<String, String> optionData = context.getDataContext().get(RUNDECK_DATA_CONTEXT_OPTION_KEY);
            String authToken = authenticate(client, optionData.get(SALT_USER_PARAM),
                    optionData.get(SALT_PASSWORD_PARAM));

            if (authToken == null) {
                throw new NodeStepException("Authentication failure",
                        SaltApiNodeStepFailureReason.AUTHENTICATION_FAILURE, entry.getNodename());
            }

            try {
                String dispatchedJid = submitJob(context, client, authToken, entry.getNodename());
                String jobOutput = waitForJidResponse(context, client, authToken, dispatchedJid, entry.getNodename());
                context.getLogger().log(Constants.INFO_LEVEL, jobOutput);
            }
            catch (InterruptedException e) {
                throw new NodeStepException(e.getMessage(), SaltApiNodeStepFailureReason.INTERRUPTED,
                        entry.getNodename());
            } catch (SaltTargettingMismatchException e) {
                throw new NodeStepException(e.getMessage(), SaltApiNodeStepFailureReason.SALT_TARGET_MISMATCH,
                        entry.getNodename());
            } catch (SaltApiResponseException e) {
                throw new NodeStepException(e.getMessage(), SaltApiNodeStepFailureReason.SALT_API_FAILURE,
                        entry.getNodename());
            }
        } catch (HttpException e) {
            throw new NodeStepException(e.getMessage(), SaltApiNodeStepFailureReason.COMMUNICATION_FAILURE,
                    entry.getNodename());
        } catch (IOException e) {
            throw new NodeStepException(e.getMessage(), SaltApiNodeStepFailureReason.COMMUNICATION_FAILURE,
                    entry.getNodename());
        }
    }

    /**
     * Submits the job to salt-api using the class function and args.
     * 
     * @return the jid of the submitted job
     * @throws HttpException
     *             if there was a communication failure with salt-api
     */
    protected String submitJob(PluginStepContext context, HttpClient client, String authToken, String minionId)
            throws HttpException, IOException, SaltApiResponseException, SaltTargettingMismatchException {
        StringBuilder bodyString = new StringBuilder();
        List<String> args = ArgumentSplitterUtil.split(function);
        bodyString.append(SALT_API_FUNCTION_PARAM_NAME).append("=")
                .append(URLEncoder.encode(args.get(0), CHAR_SET_ENCODING)).append("&")
                .append(SALT_API_TARGET_PARAM_NAME).append("=").append(URLEncoder.encode(minionId, CHAR_SET_ENCODING));
        for (int i = 1; i < args.size(); i++) {
            bodyString.append("&").append(SALT_API_ARGUMENTS_PARAM_NAME).append("=")
                    .append(URLEncoder.encode(args.get(i), CHAR_SET_ENCODING));
        }

        PostMethod post = httpFactory.createPostMethod(saltEndpoint + MINION_RESOURCE);
        try {
            post.setRequestHeader(SALT_AUTH_TOKEN_HEADER, authToken);
            post.setRequestHeader(REQUEST_ACCEPT_HEADER_NAME, JSON_RESPONSE_ACCEPT_TYPE);
            post.setRequestEntity(new StringRequestEntity(bodyString.toString(), REQUEST_CONTENT_TYPE,
                    CHAR_SET_ENCODING));
            client.executeMethod(post);

            if (post.getStatusCode() != HttpStatus.SC_ACCEPTED) {
                throw new HttpException(String.format("Expected response code %d, received %d. %s",
                        HttpStatus.SC_ACCEPTED, post.getStatusCode(), post.getResponseBodyAsString()));
            } else {
                String response = post.getResponseBodyAsString();
                context.getLogger().log(Constants.DEBUG_LEVEL,
                        String.format("Received response for job submission = %s", response));
                Gson gson = new Gson();
                List<Map<String, Object>> responses = gson.fromJson(response, MINION_RESPONSE_TYPE);
                if (responses.size() != 1) {
                    throw new SaltApiResponseException(String.format("Could not understand salt response %s", response));
                }
                Map<String, Object> responseMap = responses.get(0);
                SaltApiResponseOutput saltOutput = gson.fromJson(responseMap.get(SALT_OUTPUT_RETURN_KEY).toString(),
                        SaltApiResponseOutput.class);
                if (saltOutput.getMinions().size() != 1) {
                    throw new SaltTargettingMismatchException(String.format(
                            "Expected minion delegation count of 1, was %d. Full minion string: (%s)", saltOutput
                                    .getMinions().size(), saltOutput.getMinions()));
                } else if (!saltOutput.getMinions().contains(minionId)) {
                    throw new SaltTargettingMismatchException(String.format(
                            "Minion dispatch mis-match. Expected:%s,  was:%s", minionId, saltOutput.getMinions()
                                    .toString()));
                }
                return saltOutput.getJid();
            }
        } finally {
            post.releaseConnection();
        }
    }

    protected String waitForJidResponse(PluginStepContext context, HttpClient client, String authToken, String jid,
            String minionId) throws IOException, InterruptedException, SaltApiResponseException {
        do {
            String response = extractOutputForJid(context, client, authToken, jid, minionId);
            if (response != null) {
                return response;
            }
            Thread.sleep(pollFrequency);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (true);
    }

    /**
     * Extracts the minion job response by calling the job resource.
     * 
     * @return the host response or null if none is available.
     * @throws SaltApiResponseException
     *             if the salt-api response does not conform to the expected
     *             format.
     */
    protected String extractOutputForJid(PluginStepContext context, HttpClient client, String authToken, String jid,
            String minionId) throws IOException, SaltApiResponseException {
        String jidResource = String.format("%s%s/%s", saltEndpoint, JOBS_RESOURCE, jid);
        GetMethod method = httpFactory.createGetMethod(jidResource);
        try {
            method.setRequestHeader(SALT_AUTH_TOKEN_HEADER, authToken);
            method.setRequestHeader(REQUEST_ACCEPT_HEADER_NAME, JSON_RESPONSE_ACCEPT_TYPE);
            client.executeMethod(method);

            if (method.getStatusCode() == HttpStatus.SC_OK) {
                String response = method.getResponseBodyAsString();
                context.getLogger().log(Constants.DEBUG_LEVEL,
                        String.format("Received response for jobs/%s = %s", jid, response));
                Gson gson = new Gson();
                Map<String, List<Map<String, Object>>> result = gson.fromJson(response, JOB_RESPONSE_TYPE);
                List<Map<String, Object>> responses = result.get(SALT_OUTPUT_RETURN_KEY);
                if (responses.size() > 1) {
                    throw new SaltApiResponseException("Too many responses received: " + response);
                } else if (responses.size() == 1) {
                    Map<String, Object> minionResponse = responses.get(0);
                    if (minionResponse.containsKey(minionId)) {
                        Object responseObj = minionResponse.get(minionId);
                        return responseObj.toString();
                    }
                }
                return null;
            } else {
                return null;
            }
        } finally {
            method.releaseConnection();
        }
    }

    /**
     * Authenticates the given username/password with the given eauth system
     * against the salt-api endpoint
     * 
     * @param user
     *            The user to auth with
     * @param password
     *            The password for the given user
     * @return X-Auth-Token for use in subsequent requests
     */
    protected String authenticate(HttpClient client, String user, String password) throws IOException {
        StringBuilder bodyString = new StringBuilder();
        bodyString.append(SALT_API_USERNAME_PARAM_NAME).append("=").append(URLEncoder.encode(user, CHAR_SET_ENCODING))
                .append("&").append(SALT_API_PASSWORD_PARAM_NAME).append("=")
                .append(URLEncoder.encode(password, CHAR_SET_ENCODING)).append("&").append(SALT_API_EAUTH_PARAM_NAME)
                .append("=").append(eAuth);

        PostMethod method = httpFactory.createPostMethod(saltEndpoint + LOGIN_RESOURCE);
        try {
            method.setRequestEntity(new StringRequestEntity(bodyString.toString(), REQUEST_CONTENT_TYPE,
                    CHAR_SET_ENCODING));
            client.executeMethod(method);

            return method.getStatusCode() == HttpStatus.SC_MOVED_TEMPORARILY ? method.getResponseHeader(
                    SALT_AUTH_TOKEN_HEADER).getValue() : null;
        } finally {
            method.releaseConnection();
        }
    }
}