/*
 * Copyright 2014-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.zookeeper.leader;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListenerAdapter;

import org.springframework.context.SmartLifecycle;
import org.springframework.integration.leader.Candidate;
import org.springframework.integration.leader.Context;
import org.springframework.integration.leader.event.LeaderEventPublisher;
import org.springframework.util.StringUtils;

/**
 * Bootstrap leadership {@link Candidate candidates}
 * with ZooKeeper/Curator. Upon construction, {@link #start} must be invoked to
 * register the candidate for leadership election.
 *
 * @author Patrick Peralta
 * @author Janne Valkealahti
 * @author Gary Russell
 * @since 4.2
 */
public class LeaderInitiator implements SmartLifecycle {

	private static final Log logger = LogFactory.getLog(LeaderInitiator.class);

	private static final String DEFAULT_NAMESPACE = "/spring-integration/leader/";

	/**
	 * Curator client.
	 */
	private final CuratorFramework client;

	/**
	 * Candidate for leader election.
	 */
	private final Candidate candidate;

	private final Object lifecycleMonitor = new Object();

	/**
	 * Curator utility for selecting leaders.
	 */
	private volatile LeaderSelector leaderSelector;

	/**
	 * @see SmartLifecycle
	 */
	private volatile boolean autoStartup = true;

	/**
	 * @see SmartLifecycle which is an extension of org.springframework.context.Phased
	 */
	private volatile int phase;

	/**
	 * Flag that indicates whether the leadership election for
	 * this {@link #candidate} is running.
	 */
	private volatile boolean running;

	/** Base path in a zookeeper */
	private final String namespace;

	/** Leader event publisher if set */
	private volatile LeaderEventPublisher leaderEventPublisher;

	/**
	 * Construct a {@link LeaderInitiator}.
	 *
	 * @param client     Curator client
	 * @param candidate  leadership election candidate
	 */
	public LeaderInitiator(CuratorFramework client, Candidate candidate) {
		this(client, candidate, DEFAULT_NAMESPACE);
	}

	/**
	 * Construct a {@link LeaderInitiator}.
	 *
	 * @param client     Curator client
	 * @param candidate  leadership election candidate
	 * @param namespace  namespace base path in zookeeper
	 */
	public LeaderInitiator(CuratorFramework client, Candidate candidate, String namespace) {
		this.client = client;
		this.candidate = candidate;
		this.namespace = namespace;
	}

	/**
	 * @return true if leadership election for this {@link #candidate} is running
	 */
	@Override
	public boolean isRunning() {
		return this.running;
	}

	@Override
	public int getPhase() {
		return this.phase;
	}

	/**
	 * @param phase the phase
	 * @see SmartLifecycle
	 */
	public void setPhase(int phase) {
		this.phase = phase;
	}

	@Override
	public boolean isAutoStartup() {
		return this.autoStartup;
	}

	/**
	 * @param autoStartup true to start automatically
	 * @see SmartLifecycle
	 */
	public void setAutoStartup(boolean autoStartup) {
		this.autoStartup = autoStartup;
	}

	/**
	 * Start the registration of the {@link #candidate} for leader election.
	 */
	@Override
	public void start() {
		synchronized (this.lifecycleMonitor) {
			if (!this.running) {
				if (this.client.getState() != CuratorFrameworkState.STARTED) {
					// we want to do curator start here because it needs to
					// be started before leader selector and it gets a little
					// complicated to control ordering via beans so that
					// curator is fully started.
					this.client.start();
				}
				this.leaderSelector = new LeaderSelector(this.client, buildLeaderPath(), new LeaderListener());
				this.leaderSelector.setId(this.candidate.getId());
				this.leaderSelector.autoRequeue();
				this.leaderSelector.start();

				this.running = true;
				logger.debug("Started LeaderInitiator");
			}
		}
	}

	/**
	 * Stop the registration of the {@link #candidate} for leader election.
	 * If the candidate is currently leader, its leadership will be revoked.
	 */
	@Override
	public void stop() {
		synchronized (this.lifecycleMonitor) {
			if (this.running) {
				this.leaderSelector.close();
				this.running = false;
				logger.debug("Stopped LeaderInitiator");
			}
		}
	}

	@Override
	public void stop(Runnable runnable) {
		stop();
		runnable.run();
	}

	/**
	 * Sets the {@link LeaderEventPublisher}.
	 *
	 * @param leaderEventPublisher the event publisher
	 */
	public void setLeaderEventPublisher(LeaderEventPublisher leaderEventPublisher) {
		this.leaderEventPublisher = leaderEventPublisher;
	}

	/**
	 * @return the ZooKeeper path used for leadership election by Curator
	 */
	private String buildLeaderPath() {

		String ns = StringUtils.hasText(this.namespace) ? this.namespace : DEFAULT_NAMESPACE;
		if (!ns.startsWith("/")) {
			ns = "/" + ns;
		}
		if (!ns.endsWith("/")) {
			ns = ns + "/";
		}
		return ns + this.candidate.getRole();
	}

	/**
	 * Implementation of Curator leadership election listener.
	 */
	protected class LeaderListener extends LeaderSelectorListenerAdapter {

		@Override
		public void takeLeadership(CuratorFramework framework) throws Exception {
			CuratorContext context = new CuratorContext();

			try {
				LeaderInitiator.this.candidate.onGranted(context);
				if (LeaderInitiator.this.leaderEventPublisher != null) {
					LeaderInitiator.this.leaderEventPublisher.publishOnGranted(LeaderInitiator.this, context,
							LeaderInitiator.this.candidate.getRole());
				}

				// when this method exits, the leadership will be revoked;
				// therefore this thread needs to be held up until the
				// candidate is no longer leader
				Thread.sleep(Long.MAX_VALUE);
			}
			catch (InterruptedException e) {
				// InterruptedException, like any other runtime exception,
				// is handled by the finally block below. No need to
				// reset the interrupt flag as the interrupt is handled.
			}
			finally {
				LeaderInitiator.this.candidate.onRevoked(context);
				if (LeaderInitiator.this.leaderEventPublisher != null) {
					LeaderInitiator.this.leaderEventPublisher.publishOnRevoked(LeaderInitiator.this, context,
							LeaderInitiator.this.candidate.getRole());
				}
			}
		}
	}

	/**
	 * Implementation of leadership context backed by Curator.
	 */
	private class CuratorContext implements Context {

		CuratorContext() {
			super();
		}

		@Override
		public boolean isLeader() {
			return LeaderInitiator.this.leaderSelector.hasLeadership();
		}

		@Override
		public void yield() {
			LeaderInitiator.this.leaderSelector.interruptLeadership();
		}

		@Override
		public String toString() {
			return "LockContext{role=" + LeaderInitiator.this.candidate.getRole() +
					", id=" + LeaderInitiator.this.candidate.getId() +
					", isLeader=" + isLeader() + "}";
		}

	}

}