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
package com.netflix.genie.server.services.impl.jpa;

import com.netflix.client.ClientFactory;
import com.netflix.client.http.HttpRequest;
import com.netflix.client.http.HttpRequest.Verb;
import com.netflix.client.http.HttpResponse;
import com.netflix.config.ConfigurationManager;
import com.netflix.genie.common.exceptions.CloudServiceException;
import com.netflix.genie.common.model.Job;
import com.netflix.genie.common.model.Job_;
import com.netflix.genie.common.model.Types.JobStatus;
import com.netflix.genie.common.model.Types.SubprocessStatus;
import com.netflix.genie.server.jobmanager.JobManagerFactory;
import com.netflix.genie.server.metrics.GenieNodeStatistics;
import com.netflix.genie.server.metrics.JobCountManager;
import com.netflix.genie.server.persistence.PersistenceManager;
import com.netflix.genie.server.services.ExecutionService;
import com.netflix.genie.server.util.NetUtil;
import com.netflix.niws.client.http.RestClient;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.persistence.EntityExistsException;
import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import javax.persistence.RollbackException;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import org.apache.commons.configuration.AbstractConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of the Genie Execution Service API that uses a local job
 * launcher (via the job manager implementation), and uses OpenJPA for
 * peristence.
 *
 * @author skrishnan
 * @author bmundlapudi
 * @author amsharma
 * @author tgianos
 */
public class ExecutionServiceJPAImpl implements ExecutionService {

    private static final Logger LOG = LoggerFactory
            .getLogger(ExecutionServiceJPAImpl.class);

    // instance of the netflix configuration object
    private static final AbstractConfiguration CONF;

    // these can be over-ridden in the properties file
    private static final int SERVER_PORT;
    private static final String JOB_DIR_PREFIX;
    private static final String JOB_RESOURCE_PREFIX;

    // per-instance variables
    private final PersistenceManager<Job> pm;
    private final GenieNodeStatistics stats;

    // initialize static variables
    static {
        CONF = ConfigurationManager.getConfigInstance();
        SERVER_PORT = CONF.getInt("netflix.appinfo.port", 7001);
        JOB_DIR_PREFIX = CONF.getString("netflix.genie.server.job.dir.prefix",
                "genie-jobs");
        JOB_RESOURCE_PREFIX = CONF.getString(
                "netflix.genie.server.job.resource.prefix", "genie/v1/jobs");
    }

    /**
     * Default constructor - initializes persistence manager, and other utility
     * classes.
     */
    public ExecutionServiceJPAImpl() {
        this.pm = new PersistenceManager<Job>();
        this.stats = GenieNodeStatistics.getInstance();
    }

    /**
     * {@inheritDoc}
     *
     * @throws CloudServiceException
     */
    @Override
    public Job submitJob(final Job job) throws CloudServiceException {
        LOG.debug("Called");

        // validate parameters
        Job.validate(job);

        // generate job id, if need be
        if (StringUtils.isEmpty(job.getId())) {
            job.setId(UUID.randomUUID().toString());
        }

        job.setJobStatus(JobStatus.INIT, "Initializing job");

        // ensure that job won't overload system
        // synchronize until an entry is created and INIT-ed in DB
        // throttling related parameters
        final int maxRunningJobs = CONF.getInt(
                "netflix.genie.server.max.running.jobs", 0);
        final int jobForwardThreshold = CONF.getInt(
                "netflix.genie.server.forward.jobs.threshold", 0);
        final int maxIdleHostThreshold = CONF.getInt(
                "netflix.genie.server.max.idle.host.threshold", 0);
        final int idleHostThresholdDelta = CONF.getInt(
                "netflix.genie.server.idle.host.threshold.delta", 0);
        synchronized (this) {
            final int numRunningJobs = JobCountManager.getNumInstanceJobs();
            LOG.info("Number of running jobs: " + numRunningJobs);

            // find an instance with fewer than (numRunningJobs -
            // idleHostThresholdDelta)
            int idleHostThreshold = numRunningJobs - idleHostThresholdDelta;
            // if numRunningJobs is already >= maxRunningJobs, forward
            // aggressively
            // but cap it at the max
            if ((idleHostThreshold > maxIdleHostThreshold)
                    || (numRunningJobs >= maxRunningJobs)) {
                idleHostThreshold = maxIdleHostThreshold;
            }

            // check to see if job should be forwarded - only forward it
            // once. the assumption is that jobForwardThreshold < maxRunningJobs
            // (set in properties file)
            if (numRunningJobs >= jobForwardThreshold && !job.isForwarded()) {
                LOG.info("Number of running jobs greater than forwarding threshold - trying to auto-forward");
                final String idleHost = JobCountManager
                        .getIdleInstance(idleHostThreshold);
                if (!idleHost.equals(NetUtil.getHostName())) {
                    job.setForwarded(true);
                    this.stats.incrGenieForwardedJobs();
                    return forwardJobRequest("http://" + idleHost + ":"
                            + SERVER_PORT + "/" + JOB_RESOURCE_PREFIX, job);
                } // else, no idle hosts found - run here if capacity exists
            }

            if (numRunningJobs >= maxRunningJobs) {
                // if we get here, job can't be forwarded to an idle
                // instance anymore and current node is overloaded
                throw new CloudServiceException(
                        HttpURLConnection.HTTP_UNAVAILABLE,
                        "Number of running jobs greater than system limit ("
                        + maxRunningJobs
                        + ") - try another instance or try again later");
            }

            // if job can be launched, update the URIs
            buildJobURIs(job);

            // init state in DB - return if job already exists
            try {
                // TODO add retries to avoid deadlock issue
                this.pm.createEntity(job);
            } catch (final RollbackException e) {
                LOG.error("Can't create entity in the database", e);
                if (e.getCause() instanceof EntityExistsException) {
                    // most likely entity already exists - return useful message
                    throw new CloudServiceException(
                            HttpURLConnection.HTTP_CONFLICT,
                            "Job already exists for id: " + job.getId());
                } else {
                    // unknown exception - send it back
                    throw new CloudServiceException(
                            HttpURLConnection.HTTP_INTERNAL_ERROR,
                            e);
                }
            }
        } // end synchronize

        // increment number of submitted jobs
        this.stats.incrGenieJobSubmissions();

        // try to run the job - return success or error
        try {
            final JobManagerFactory factory = new JobManagerFactory();
            factory.getJobManager(job).launch(job);

            // update entity in DB
            job.setUpdated(new Date());
            this.pm.updateEntity(job);
            return job;
        } catch (final CloudServiceException e) {
            LOG.error("Failed to submit job: ", e);
            // update db
            job.setJobStatus(JobStatus.FAILED, e.getMessage());
            this.pm.updateEntity(job);
            // increment counter for failed jobs
            this.stats.incrGenieFailedJobs();
            // if it is a known exception, handle differently
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws CloudServiceException
     */
    @Override
    public Job getJobInfo(final String id) throws CloudServiceException {
        if (StringUtils.isEmpty(id)) {
            throw new CloudServiceException(
                    HttpURLConnection.HTTP_BAD_REQUEST,
                    "No id entered unable to retreive job.");
        }
        LOG.debug("called for jobId: " + id);

        final EntityManager em = this.pm.createEntityManager();
        try {
            final Job job = em.find(Job.class, id);
            if (job != null) {
                return job;
            } else {
                throw new CloudServiceException(
                        HttpURLConnection.HTTP_NOT_FOUND,
                        "No job exists for id " + id + ". Unable to retrieve.");
            }
        } finally {
            em.close();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws CloudServiceException
     */
    @Override
    public List<Job> getJobs(
            final String id,
            final String jobName,
            final String userName,
            final JobStatus status,
            final String clusterName,
            final String clusterId,
            final int limit,
            final int page) throws CloudServiceException {
        LOG.debug("called");

        final EntityManager em = pm.createEntityManager();
        try {
            final CriteriaBuilder cb = em.getCriteriaBuilder();
            final CriteriaQuery<Job> cq = cb.createQuery(Job.class);
            final Root<Job> j = cq.from(Job.class);
            final List<Predicate> predicates = new ArrayList<Predicate>();
            if (StringUtils.isNotEmpty(userName)) {
                predicates.add(cb.like(j.get(Job_.id), id));
            }
            if (StringUtils.isNotEmpty(jobName)) {
                predicates.add(cb.like(j.get(Job_.name), jobName));
            }
            if (StringUtils.isNotEmpty(userName)) {
                predicates.add(cb.equal(j.get(Job_.user), userName));
            }
            if (status != null) {
                predicates.add(cb.equal(j.get(Job_.status), status));
            }
            if (StringUtils.isNotEmpty(clusterName)) {
                predicates.add(cb.equal(j.get(Job_.executionClusterName), clusterName));
            }
            if (StringUtils.isNotEmpty(clusterId)) {
                predicates.add(cb.equal(j.get(Job_.executionClusterId), clusterId));
            }
            cq.where(cb.and(predicates.toArray(new Predicate[0])));
            final TypedQuery<Job> query = em.createQuery(cq);
            final int finalPage = page < 0 ? PersistenceManager.DEFAULT_PAGE_NUMBER : page;
            final int finalLimit = limit < 0 ? PersistenceManager.DEFAULT_PAGE_SIZE : limit;
            query.setMaxResults(finalLimit);
            query.setFirstResult(finalLimit * finalPage);
            return query.getResultList();
        } finally {
            em.close();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws CloudServiceException
     */
    @Override
    public JobStatus getJobStatus(final String id) throws CloudServiceException {
        return getJobInfo(id).getStatus();
    }

    /**
     * {@inheritDoc}
     *
     * @throws CloudServiceException
     */
    @Override
    public Job killJob(final String id) throws CloudServiceException {
        if (StringUtils.isEmpty(id)) {
            throw new CloudServiceException(
                    HttpURLConnection.HTTP_BAD_REQUEST,
                    "No id entered unable to kill job.");
        }
        LOG.debug("called for jobId: " + id);

        final EntityManager em = this.pm.createEntityManager();
        final EntityTransaction trans = em.getTransaction();
        try {
            trans.begin();
            final Job job = em.find(Job.class, id);

            // do some basic error handling
            if (job == null) {
                throw new CloudServiceException(
                        HttpURLConnection.HTTP_NOT_FOUND,
                        "No job exists for id " + id + ". Unable to kill.");
            }

            // check if it is done already
            if (job.getStatus() == JobStatus.SUCCEEDED
                    || job.getStatus() == JobStatus.KILLED
                    || job.getStatus() == JobStatus.FAILED) {
                // job already exited, return status to user
                return job;
            } else if (job.getStatus() == JobStatus.INIT
                    || (job.getProcessHandle() == -1)) {
                // can't kill a job if it is still initializing
                throw new CloudServiceException(
                        HttpURLConnection.HTTP_INTERNAL_ERROR,
                        "Unable to kill job as it is still initializing");
            }

            // if we get here, job is still running - and can be killed
            // redirect to the right node if killURI points to a different node
            final String killURI = job.getKillURI();
            if (StringUtils.isEmpty(killURI)) {
                throw new CloudServiceException(
                        HttpURLConnection.HTTP_INTERNAL_ERROR,
                        "Failed to get killURI for jobID: " + id);
            }
            final String localURI = getEndPoint() + "/" + JOB_RESOURCE_PREFIX + "/" + id;

            if (!killURI.equals(localURI)) {
                LOG.debug("forwarding kill request to: " + killURI);
                return forwardJobKill(killURI);
            }

            // if we get here, killURI == localURI, and job should be killed here
            LOG.debug("killing job on same instance: " + id);
            final JobManagerFactory factory = new JobManagerFactory();
            factory.getJobManager(job).kill(job);

            job.setJobStatus(JobStatus.KILLED, "Job killed on user request");
            job.setExitCode(SubprocessStatus.JOB_KILLED.code());

            // increment counter for killed jobs
            this.stats.incrGenieKilledJobs();

            // update final status in DB
            final ReentrantReadWriteLock rwl = PersistenceManager.getDbLock();
            try {
                LOG.debug("updating job status to KILLED for: " + id);
                // acquire write lock first, and then update status
                // if job status changed between when it was read and now,
                // this thread will simply overwrite it - final state will be KILLED
                rwl.writeLock().lock();
                if (!job.isDisableLogArchival()) {
                    job.setArchiveLocation(NetUtil.getArchiveURI(id));
                }
                trans.commit();
            } catch (final Exception e) {
                throw new CloudServiceException(
                        HttpURLConnection.HTTP_INTERNAL_ERROR,
                        e.getMessage());
            } finally {
                if (rwl.writeLock().isHeldByCurrentThread()) {
                    rwl.writeLock().unlock();
                }
            }

            // all good - return results
            return job;
        } finally {
            if (trans.isActive()) {
                trans.rollback();
            }
            em.close();
        }
    }

    private void buildJobURIs(final Job job) throws CloudServiceException {
        job.setHostName(NetUtil.getHostName());
        job.setOutputURI(getEndPoint() + "/" + JOB_DIR_PREFIX + "/" + job.getId());
        job.setKillURI(getEndPoint() + "/" + JOB_RESOURCE_PREFIX + "/" + job.getId());
    }

    private String getEndPoint() throws CloudServiceException {
        return "http://" + NetUtil.getHostName() + ":" + SERVER_PORT;
    }

    private Job forwardJobKill(final String killURI) throws CloudServiceException {
        return executeRequest(Verb.DELETE, killURI, null);
    }

    private Job forwardJobRequest(
            final String hostURI,
            final Job job) throws CloudServiceException {
        return executeRequest(Verb.POST, hostURI, job);
    }

    private Job executeRequest(
            final Verb method,
            final String restURI,
            final Job job)
            throws CloudServiceException {
        HttpResponse clientResponse = null;
        try {
            final RestClient genieClient = (RestClient) ClientFactory
                    .getNamedClient("genie");
            final HttpRequest req = HttpRequest.newBuilder()
                    .verb(method).header("Accept", "application/json")
                    .uri(new URI(restURI)).entity(job).build();
            clientResponse = genieClient.execute(req);
            if (clientResponse != null) {
                int status = clientResponse.getStatus();
                LOG.info("Response Status: " + status);
                return clientResponse.getEntity(Job.class);
            } else {
                String msg = "Received null response while auto-forwarding request to Genie instance";
                LOG.error(msg);
                throw new CloudServiceException(
                        HttpURLConnection.HTTP_INTERNAL_ERROR, msg);
            }
        } catch (final CloudServiceException e) {
            // just raise it rightaway
            throw e;
        } catch (final Exception e) {
            final String msg = "Error while trying to auto-forward request: "
                    + e.getMessage();
            LOG.error(msg, e);
            throw new CloudServiceException(HttpURLConnection.HTTP_INTERNAL_ERROR, msg);
        } finally {
            if (clientResponse != null) {
                // this is really really important
                clientResponse.close();
            }
        }
    }
}