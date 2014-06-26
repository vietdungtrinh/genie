/*
 *
 *  Copyright 2014 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.genie.client;

import com.google.common.collect.Multimap;
import com.netflix.client.http.HttpRequest;
import com.netflix.client.http.HttpRequest.Verb;
import static com.netflix.genie.client.BaseGenieClient.SLASH;
import com.netflix.genie.common.exceptions.CloudServiceException;
import com.netflix.genie.common.model.Application;
import com.netflix.genie.common.model.Cluster;
import com.netflix.genie.common.model.Command;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton class, which acts as the client library for the Command
 * Configuration Service.
 *
 * @author tgianos
 */
public final class CommandServiceClient extends BaseGenieClient {

    private static final Logger LOG = LoggerFactory
            .getLogger(CommandServiceClient.class);

    private static final String BASE_CONFIG_COMMAND_REST_URL
            = BASE_REST_URL + "config/commands";

    // reference to the instance object
    private static CommandServiceClient instance;

    /**
     * Private constructor for singleton class.
     *
     * @throws IOException if there is any error during initialization
     */
    private CommandServiceClient() throws IOException {
        super();
    }

    /**
     * Returns the singleton instance that can be used by clients.
     *
     * @return ExecutionServiceClient instance
     * @throws IOException if there is an error instantiating client
     */
    public static synchronized CommandServiceClient getInstance() throws IOException {
        if (instance == null) {
            instance = new CommandServiceClient();
        }

        return instance;
    }

    /**
     * Create a new command configuration.
     *
     * @param command the object encapsulating the new Cluster configuration to
     * create
     *
     * @return extracted command configuration response
     * @throws CloudServiceException
     */
    public Command createCommand(final Command command)
            throws CloudServiceException {
        Command.validate(command);
        final HttpRequest request = this.buildRequest(
                Verb.POST,
                BASE_CONFIG_COMMAND_REST_URL,
                null,
                command);
        return (Command) this.executeRequest(request, null, Command.class);
    }

    /**
     * Create or update a command configuration.
     *
     * @param id the id for the command configuration to create or update
     * @param command the object encapsulating the new Cluster configuration to
     * create
     *
     * @return extracted command configuration response
     * @throws CloudServiceException
     */
    public Command updateCommand(final String id, final Command command)
            throws CloudServiceException {
        if (StringUtils.isBlank(id)) {
            String msg = "Required parameter id can't be null or empty.";
            LOG.error(msg);
            throw new CloudServiceException(HttpURLConnection.HTTP_BAD_REQUEST, msg);
        }

        final HttpRequest request = this.buildRequest(
                Verb.PUT,
                StringUtils.join(
                        new String[]{BASE_CONFIG_COMMAND_REST_URL, id},
                        SLASH),
                null,
                command);
        return (Command) this.executeRequest(request, null, Command.class);
    }

    /**
     * Gets information for a given configId.
     *
     * @param id the command configuration id to get (can't be null or empty)
     * @return the command configuration for this id
     * @throws CloudServiceException
     */
    public Command getCommand(final String id) throws CloudServiceException {
        if (StringUtils.isBlank(id)) {
            final String msg = "Missing required parameter: id";
            LOG.error(msg);
            throw new CloudServiceException(HttpURLConnection.HTTP_BAD_REQUEST, msg);
        }

        final HttpRequest request = this.buildRequest(
                Verb.GET,
                StringUtils.join(
                        new String[]{BASE_CONFIG_COMMAND_REST_URL, id},
                        SLASH),
                null,
                null);
        return (Command) this.executeRequest(request, null, Command.class);
    }

    /**
     * Gets a set of command configurations for the given parameters.
     *
     * @param params key/value pairs in a map object.<br>
     *
     * More details on the parameters can be found on the Genie User Guide on
     * GitHub.
     * @return List of command configuration elements that match the filter
     * @throws CloudServiceException
     */
    public List<Command> getCommands(final Multimap<String, String> params)
            throws CloudServiceException {
        final HttpRequest request = this.buildRequest(
                Verb.GET,
                BASE_CONFIG_COMMAND_REST_URL,
                params,
                null);
        return (List<Command>) this.executeRequest(request, List.class, Command.class);
    }

    /**
     * Delete all the commands in the database.
     *
     * @return the should be empty set.
     * @throws CloudServiceException
     */
    public List<Command> deleteAllCommands() throws CloudServiceException {
        final HttpRequest request = this.buildRequest(
                Verb.DELETE,
                BASE_CONFIG_COMMAND_REST_URL,
                null,
                null);
        return (List<Command>) this.executeRequest(request, List.class, Command.class);
    }

    /**
     * Delete a configuration using its id.
     *
     * @param id the id for the command configuration to delete. Not null or
     * empty.
     * @return the deleted command configuration
     * @throws CloudServiceException
     */
    public Command deleteCommand(final String id) throws CloudServiceException {
        if (StringUtils.isBlank(id)) {
            String msg = "Missing required parameter: id";
            LOG.error(msg);
            throw new CloudServiceException(HttpURLConnection.HTTP_BAD_REQUEST, msg);
        }

        final HttpRequest request = this.buildRequest(
                Verb.DELETE,
                StringUtils.join(
                        new String[]{BASE_CONFIG_COMMAND_REST_URL, id},
                        SLASH),
                null,
                null);
        return (Command) this.executeRequest(request, null, Command.class);
    }

    /**
     * Add some more configuration files to a given command.
     *
     * @param id The id of the command to add configurations to. Not
     * Null/empty/blank.
     * @param configs The configuration files to add. Not null or empty.
     * @return The new set of configuration files for the given command.
     * @throws CloudServiceException
     */
    public Set<String> addConfigsToCommand(
            final String id,
            final Set<String> configs) throws CloudServiceException {
        if (StringUtils.isBlank(id)) {
            final String msg = "Missing required parameter: id";
            LOG.error(msg);
            throw new CloudServiceException(HttpURLConnection.HTTP_BAD_REQUEST, msg);
        }
        if (configs == null || configs.isEmpty()) {
            final String msg = "Missing required parameter: configs";
            LOG.error(msg);
            throw new CloudServiceException(HttpURLConnection.HTTP_BAD_REQUEST, msg);
        }

        final HttpRequest request = this.buildRequest(
                Verb.POST,
                StringUtils.join(
                        new String[]{BASE_CONFIG_COMMAND_REST_URL, id, "configs"},
                        SLASH),
                null,
                configs);
        return (Set<String>) this.executeRequest(request, Set.class, String.class);
    }

    /**
     * Get the active set of configuration files for the given command.
     *
     * @param id The id of the command to get configurations for. Not
     * Null/empty/blank.
     * @return The set of configuration files for the given command.
     * @throws CloudServiceException
     */
    public Set<String> getConfigsForCommand(final String id) throws CloudServiceException {
        if (StringUtils.isBlank(id)) {
            String msg = "Missing required parameter: id";
            LOG.error(msg);
            throw new CloudServiceException(HttpURLConnection.HTTP_BAD_REQUEST, msg);
        }

        final HttpRequest request = this.buildRequest(
                Verb.GET,
                StringUtils.join(
                        new String[]{BASE_CONFIG_COMMAND_REST_URL, id, "configs"},
                        SLASH),
                null,
                null);
        return (Set<String>) this.executeRequest(request, Set.class, String.class);
    }

    /**
     * Update the configuration files for a given command.
     *
     * @param id The id of the command to update the configuration files for.
     * Not null/empty/blank.
     * @param configs The configuration files to replace existing configuration
     * files with. Not null.
     * @return The new set of command configurations.
     * @throws CloudServiceException
     */
    public Set<String> updateConfigsForCommand(
            final String id,
            final Set<String> configs) throws CloudServiceException {
        if (StringUtils.isBlank(id)) {
            final String msg = "Missing required parameter: id";
            LOG.error(msg);
            throw new CloudServiceException(
                    HttpURLConnection.HTTP_BAD_REQUEST, msg);
        }
        if (configs == null) {
            final String msg = "Missing required parameter: configs";
            LOG.error(msg);
            throw new CloudServiceException(
                    HttpURLConnection.HTTP_BAD_REQUEST, msg);
        }

        final HttpRequest request = this.buildRequest(
                Verb.PUT,
                StringUtils.join(
                        new String[]{BASE_CONFIG_COMMAND_REST_URL, id, "configs"},
                        SLASH),
                null,
                configs);
        return (Set<String>) this.executeRequest(request, Set.class, String.class);
    }

    /**
     * Delete all the configuration files from a given command.
     *
     * @param id The id of the command to delete the configuration files from.
     * Not null/empty/blank.
     * @return Empty set if successful
     * @throws CloudServiceException
     */
    public Set<String> removeAllConfigsForCommand(
            final String id) throws CloudServiceException {
        if (StringUtils.isBlank(id)) {
            final String msg = "Missing required parameter: id";
            LOG.error(msg);
            throw new CloudServiceException(
                    HttpURLConnection.HTTP_BAD_REQUEST, msg);
        }

        final HttpRequest request = this.buildRequest(
                Verb.DELETE,
                StringUtils.join(
                        new String[]{BASE_CONFIG_COMMAND_REST_URL, id, "configs"},
                        SLASH),
                null,
                null);
        return (Set<String>) this.executeRequest(request, Set.class, String.class);
    }

    /**
     * Add some more applications to a given command.
     *
     * @param id The id of the command to add applications to. Not
     * Null/empty/blank.
     * @param applications The applications to add. Not null or empty.
     * @return The new set of application files for the given command.
     * @throws CloudServiceException
     */
    public Set<Application> addApplicationsToCommand(
            final String id,
            final Set<Application> applications) throws CloudServiceException {
        if (StringUtils.isBlank(id)) {
            final String msg = "Missing required parameter: id";
            LOG.error(msg);
            throw new CloudServiceException(HttpURLConnection.HTTP_BAD_REQUEST, msg);
        }
        if (applications == null || applications.isEmpty()) {
            final String msg = "Missing required parameter: applications";
            LOG.error(msg);
            throw new CloudServiceException(HttpURLConnection.HTTP_BAD_REQUEST, msg);
        }

        final HttpRequest request = this.buildRequest(
                Verb.POST,
                StringUtils.join(
                        new String[]{BASE_CONFIG_COMMAND_REST_URL, id, "applications"},
                        SLASH),
                null,
                applications);
        return (Set<Application>) this.executeRequest(request, Set.class, Application.class);
    }

    /**
     * Get the active set of applications for the given command.
     *
     * @param id The id of the command to get applications for. Not
     * Null/empty/blank.
     * @return The set of application files for the given command.
     * @throws CloudServiceException
     */
    public Set<Application> getApplicationsForCommand(final String id) throws CloudServiceException {
        if (StringUtils.isBlank(id)) {
            String msg = "Missing required parameter: id";
            LOG.error(msg);
            throw new CloudServiceException(
                    HttpURLConnection.HTTP_BAD_REQUEST, msg);
        }

        final HttpRequest request = this.buildRequest(
                Verb.GET,
                StringUtils.join(
                        new String[]{BASE_CONFIG_COMMAND_REST_URL, id, "applications"},
                        SLASH),
                null,
                null);
        return (Set<Application>) this.executeRequest(request, Set.class, Application.class);
    }

    /**
     * Update the applications for a given command.
     *
     * @param id The id of the command to update the application files for. Not
     * null/empty/blank.
     * @param applications The applications to replace existing application
     * files with. Not null.
     * @return The new set of command applications.
     * @throws CloudServiceException
     */
    public Set<Application> updateApplicationsForCommand(
            final String id,
            final Set<Application> applications) throws CloudServiceException {
        if (StringUtils.isBlank(id)) {
            final String msg = "Missing required parameter: id";
            LOG.error(msg);
            throw new CloudServiceException(
                    HttpURLConnection.HTTP_BAD_REQUEST, msg);
        }
        if (applications == null) {
            final String msg = "Missing required parameter: applications";
            LOG.error(msg);
            throw new CloudServiceException(
                    HttpURLConnection.HTTP_BAD_REQUEST, msg);
        }

        final HttpRequest request = this.buildRequest(
                Verb.PUT,
                StringUtils.join(
                        new String[]{BASE_CONFIG_COMMAND_REST_URL, id, "applications"},
                        SLASH),
                null,
                applications);
        return (Set<Application>) this.executeRequest(request, Set.class, Application.class);
    }

    /**
     * Delete all the applications from a given command.
     *
     * @param id The id of the command to delete the applications from. Not
     * null/empty/blank.
     * @return Empty set if successful
     * @throws CloudServiceException
     */
    public Set<Application> removeAllApplicationsForCommand(
            final String id) throws CloudServiceException {
        if (StringUtils.isBlank(id)) {
            final String msg = "Missing required parameter: id";
            LOG.error(msg);
            throw new CloudServiceException(
                    HttpURLConnection.HTTP_BAD_REQUEST, msg);
        }

        final HttpRequest request = this.buildRequest(
                Verb.DELETE,
                StringUtils.join(
                        new String[]{BASE_CONFIG_COMMAND_REST_URL, id, "applications"},
                        SLASH),
                null,
                null);
        return (Set<Application>) this.executeRequest(request, Set.class, Application.class);
    }

    /**
     * Remove an application from a given command.
     *
     * @param id The id of the command to delete the application from. Not
     * null/empty/blank.
     * @param appId The id of the application to remove. Not null/empty/blank.
     * @return The active set of applications for the command.
     * @throws CloudServiceException
     */
    public Set<Application> removeApplicationForCommand(
            final String id,
            final String appId) throws CloudServiceException {
        if (StringUtils.isBlank(id)) {
            final String msg = "Missing required parameter: id";
            LOG.error(msg);
            throw new CloudServiceException(
                    HttpURLConnection.HTTP_BAD_REQUEST, msg);
        }
        if (StringUtils.isBlank(appId)) {
            final String msg = "Missing required parameter: appId";
            LOG.error(msg);
            throw new CloudServiceException(
                    HttpURLConnection.HTTP_BAD_REQUEST, msg);
        }

        final HttpRequest request = this.buildRequest(
                Verb.DELETE,
                StringUtils.join(
                        new String[]{BASE_CONFIG_COMMAND_REST_URL, id, "applications", appId},
                        SLASH),
                null,
                null);
        return (Set<Application>) this.executeRequest(request, Set.class, Application.class);
    }

    /**
     * Get all the clusters this command is associated with.
     *
     * @param id The id of the command to get the clusters for. Not
     * NULL/empty/blank.
     * @return The set of clusters.
     * @throws CloudServiceException
     */
    public Set<Cluster> getClustersForCommand(
            final String id) throws CloudServiceException {
        if (StringUtils.isBlank(id)) {
            final String msg = "Missing required parameter: id";
            LOG.error(msg);
            throw new CloudServiceException(
                    HttpURLConnection.HTTP_BAD_REQUEST, msg);
        }

        final HttpRequest request = this.buildRequest(
                Verb.GET,
                StringUtils.join(
                        new String[]{BASE_CONFIG_COMMAND_REST_URL, id, "configs"},
                        SLASH),
                null,
                null);
        return (Set<Cluster>) this.executeRequest(request, Set.class, Cluster.class);
    }
}
