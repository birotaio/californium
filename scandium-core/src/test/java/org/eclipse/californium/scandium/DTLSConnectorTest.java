/*******************************************************************************
 * Copyright (c) 2015, 2018 Bosch Software Innovations GmbH and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Kai Hudalla (Bosch Software Innovations GmbH) - Initial creation
 *    Kai Hudalla (Bosch Software Innovations GmbH) - fix bug 464383
 *    Kai Hudalla (Bosch Software Innovations GmbH) - fix 464812
 *    Kai Hudalla (Bosch Software Innovations GmbH) - add support for stale
 *                                                    session expiration (466554)
 *    Kai Hudalla (Bosch Software Innovations GmbH) - replace SessionStore with ConnectionStore
 *                                                    keeping all information about the connection
 *                                                    to a peer in a single place
 *    Kai Hudalla (Bosch Software Innovations GmbH) - fix bug 472196
 *    Achim Kraus, Kai Hudalla (Bosch Software Innovations GmbH) - add test case for bug 478538
 *    Kai Hudalla (Bosch Software Innovations GmbH) - use DtlsTestTools' accessors to explicitly retrieve
 *                                                    client & server keys and certificate chains
 *    Bosch Software Innovations GmbH - add test cases for GitHub issue #1
 *    Achim Kraus (Bosch Software Innovations GmbH) - add tests for
 *                                                    CorrelationContextMatcher
 *    Achim Kraus (Bosch Software Innovations GmbH) - add restart test with internal executor
 *    Achim Kraus (Bosch Software Innovations GmbH) - add test for retransmission of FINISHED
 *                                                    add asserts for record sequence numbers
 *                                                    of retransmitted flights
 *    Achim Kraus (Bosch Software Innovations GmbH) - add onSent() and onError().
 *                                                    use SimpleMessageCallback
 *                                                    issue #305
 *    Achim Kraus (Bosch Software Innovations GmbH) - add check for onError() in
 *                                                    testConnectorAbortsHandshakeOnUnknownPskIdentity
 *    Achim Kraus (Bosch Software Innovations GmbH) - move correlation tests to
 *                                                    DTLSCorrelationTest.
 *    Achim Kraus (Bosch Software Innovations GmbH) - add tests for automatic resumption
 *    Vikram (University of Rostock) - add tests to check ECDHE_PSK CipherSuite
 *    Achim Kraus (Bosch Software Innovations GmbH) - remove HELLO_VERIFY_REQUEST
 *                                                    from resumption tests
 *    Achim Kraus (Bosch Software Innovations GmbH) - remove unused sendRecord
 *    Achim Kraus (Bosch Software Innovations GmbH) - move advanced tests to
 *                                                    DTLSConnectorAdvancedTest
 ******************************************************************************/
package org.eclipse.californium.scandium;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;
import static org.eclipse.californium.scandium.ConnectorHelper.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.californium.elements.AddressEndpointContext;
import org.eclipse.californium.elements.RawData;
import org.eclipse.californium.elements.util.ExecutorsUtil;
import org.eclipse.californium.elements.util.SerialExecutor;
import org.eclipse.californium.elements.util.SimpleMessageCallback;
import org.eclipse.californium.elements.util.TestThreadFactory;
import org.eclipse.californium.elements.auth.RawPublicKeyIdentity;
import org.eclipse.californium.elements.rule.TestNameLoggerRule;
import org.eclipse.californium.elements.rule.ThreadsRule;
import org.eclipse.californium.scandium.category.Medium;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.dtls.AlertMessage;
import org.eclipse.californium.scandium.dtls.AlertMessage.AlertDescription;
import org.eclipse.californium.scandium.dtls.AlertMessage.AlertLevel;
import org.eclipse.californium.scandium.dtls.CertificateType;
import org.eclipse.californium.scandium.dtls.ClientHello;
import org.eclipse.californium.scandium.dtls.ClientKeyExchange;
import org.eclipse.californium.scandium.dtls.CompressionMethod;
import org.eclipse.californium.scandium.dtls.Connection;
import org.eclipse.californium.scandium.dtls.ContentType;
import org.eclipse.californium.scandium.dtls.DTLSSession;
import org.eclipse.californium.scandium.dtls.DtlsTestTools;
import org.eclipse.californium.scandium.dtls.HandshakeException;
import org.eclipse.californium.scandium.dtls.HandshakeMessage;
import org.eclipse.californium.scandium.dtls.HandshakeType;
import org.eclipse.californium.scandium.dtls.Handshaker;
import org.eclipse.californium.scandium.dtls.HelloRequest;
import org.eclipse.californium.scandium.dtls.HelloVerifyRequest;
import org.eclipse.californium.scandium.dtls.InMemoryConnectionStore;
import org.eclipse.californium.scandium.dtls.PSKClientKeyExchange;
import org.eclipse.californium.scandium.dtls.ProtocolVersion;
import org.eclipse.californium.scandium.dtls.PskPublicInformation;
import org.eclipse.californium.scandium.dtls.Record;
import org.eclipse.californium.scandium.dtls.SessionId;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite;
import org.eclipse.californium.scandium.dtls.pskstore.InMemoryPskStore;
import org.eclipse.californium.scandium.dtls.pskstore.PskStore;
import org.eclipse.californium.scandium.dtls.pskstore.StaticPskStore;
import org.eclipse.californium.scandium.rule.DtlsNetworkRule;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies behavior of {@link DTLSConnector}.
 * <p>
 * Mainly contains integration test cases verifying the correct interaction between a client and a server.
 */
@Category(Medium.class)
public class DTLSConnectorTest {
	public static final Logger LOGGER = LoggerFactory.getLogger(DTLSConnectorTest.class.getName());

	@ClassRule
	public static DtlsNetworkRule network = new DtlsNetworkRule(DtlsNetworkRule.Mode.DIRECT, DtlsNetworkRule.Mode.NATIVE);

	@ClassRule
	public static ThreadsRule cleanup = new ThreadsRule();

	@Rule
	public TestNameLoggerRule names = new TestNameLoggerRule();

	private static final int CLIENT_CONNECTION_STORE_CAPACITY = 5;
	private static final PskPublicInformation CLIENT_IDENTITY = new PskPublicInformation("Client_identity");
	private static final String CLIENT_IDENTITY_SECRET = "secretPSK";
	private static final int MAX_TIME_TO_WAIT_SECS = 2;

	private static ConnectorHelper serverHelper;
	private static ExecutorService executor;

	DtlsConnectorConfig clientConfig;
	DTLSConnector client;
	InetSocketAddress clientEndpoint;
	LatchDecrementingRawDataChannel clientRawDataChannel;
	DTLSSession establishedClientSession;
	InMemoryConnectionStore clientConnectionStore;
	static int pskStoreLatency = 0; // in ms

	@BeforeClass
	public static void loadKeys() throws IOException, GeneralSecurityException {

		executor = ExecutorsUtil.newFixedThreadPool(2, new TestThreadFactory("DTLS-"));

		// load the key store
		InMemoryPskStore pskStore = new InMemoryPskStore() {

			@Override
			public byte[] getKey(PskPublicInformation identity) {
				if (pskStoreLatency != 0) {
					try {
						Thread.sleep(pskStoreLatency);
					} catch (InterruptedException e) {
						throw new RuntimeException(e);
					}
				}
				return super.getKey(identity);
			}
		};
		pskStore.setKey(CLIENT_IDENTITY, CLIENT_IDENTITY_SECRET.getBytes());

		DtlsConnectorConfig.Builder builder = new DtlsConnectorConfig.Builder()
			.setAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
			.setRecommendedCipherSuitesOnly(false)
			.setSupportedCipherSuites(
						CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8,
						CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256,
						CipherSuite.TLS_PSK_WITH_AES_128_CCM_8,
						CipherSuite.TLS_PSK_WITH_AES_128_CBC_SHA256,
						CipherSuite.TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256)
			.setIdentity(DtlsTestTools.getPrivateKey(), DtlsTestTools.getServerCertificateChain(), CertificateType.RAW_PUBLIC_KEY, CertificateType.X_509)
			.setTrustStore(DtlsTestTools.getTrustedCertificates())
			.setRpkTrustAll()
			.setPskStore(pskStore)
			.setClientAuthenticationRequired(true)
			.setReceiverThreadCount(1)
			.setServerOnly(true)
			.setLoggingTag("server")
			.setRetransmissionTimeout(500)
			.setMaxRetransmissions(2);
		serverHelper = new ConnectorHelper();
		serverHelper.startServer(builder);
	}

	@AfterClass
	public static void tearDown() {
		serverHelper.destroyServer();
		ExecutorsUtil.shutdownExecutorGracefully(100, executor);
	}

	@Before
	public void setUp() throws Exception {
		pskStoreLatency = 0;
		clientConnectionStore = new InMemoryConnectionStore(CLIENT_CONNECTION_STORE_CAPACITY, 60);
		clientConnectionStore.setTag("client");
		clientEndpoint = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
		clientConfig = newStandardConfig(clientEndpoint);

		client = serverHelper.createClient(clientConfig, clientConnectionStore);
		client.setExecutor(executor);

		clientRawDataChannel = new LatchDecrementingRawDataChannel();
	}

	@After
	public void cleanUp() {
		if (client != null) {
			client.destroy();
		}
		serverHelper.cleanUpServer();
	}

	private static DtlsConnectorConfig newStandardConfig(InetSocketAddress bindAddress) throws Exception {
		return newStandardConfigBuilder(bindAddress).build();
	}

	private static DtlsConnectorConfig.Builder newStandardConfigBuilder(InetSocketAddress bindAddress)  throws Exception {
		return new DtlsConnectorConfig.Builder()
				.setAddress(bindAddress)
				.setLoggingTag("client")
				.setReceiverThreadCount(1)
				.setConnectionThreadCount(2)
				.setIdentity(DtlsTestTools.getClientPrivateKey(), DtlsTestTools.getClientCertificateChain(), CertificateType.RAW_PUBLIC_KEY, CertificateType.X_509)
				.setTrustStore(DtlsTestTools.getTrustedCertificates())
				.setRpkTrustAll();
	}

	@Test
	public void testSendInvokesMessageCallbackOnSent() throws Exception {

		// GIVEN a message including a MessageCallback
		SimpleMessageCallback callback = new SimpleMessageCallback();
		RawData outboundMessage = RawData.outbound(
				new byte[]{0x01},
				new AddressEndpointContext(serverHelper.serverEndpoint),
				callback,
				false);

		// WHEN sending the message
		givenAnEstablishedSession(outboundMessage, true);

		// THEN assert that the callback has been invoked with a endpoint context
		assertTrue(callback.isSent(TimeUnit.SECONDS.toMillis(MAX_TIME_TO_WAIT_SECS)));
		assertThat(serverHelper.serverRawDataProcessor.getLatestInboundMessage(), is(notNullValue()));
		assertThat(serverHelper.serverRawDataProcessor.getLatestInboundMessage().getEndpointContext(), is(notNullValue()));
	}

	@Test
	public void testConnectorEstablishesSecureSession() throws Exception {
		givenAnEstablishedSession();
	}

	/**
	 * Verifies that a DTLSConnector terminates its connection with a peer when receiving
	 * a CLOSE_NOTIFY alert from the peer (bug #478538).
	 * 
	 * @throws Exception if test cannot be executed
	 */
	@Test
	public void testConnectorTerminatesConnectionOnReceivingCloseNotify() throws Exception {

		// GIVEN a CLOSE_NOTIFY alert
		AlertMessage alert = new AlertMessage(AlertLevel.WARNING, AlertDescription.CLOSE_NOTIFY, serverHelper.serverEndpoint);

		assertConnectionTerminatedOnAlert(alert);
	}

	/**
	 * Verifies that a DTLSConnector terminates its connection with a peer when receiving
	 * a FATAL alert from the peer.
	 * 
	 * @throws Exception if test cannot be executed
	 */
	@Test
	public void testConnectorTerminatesConnectionOnReceivingFatalAlert() throws Exception {

		// GIVEN a FATAL alert
		AlertMessage alert = new AlertMessage(AlertLevel.FATAL, AlertDescription.INTERNAL_ERROR, serverHelper.serverEndpoint);

		assertConnectionTerminatedOnAlert(alert);
	}

	private void assertConnectionTerminatedOnAlert(final AlertMessage alertToSend) throws Exception {

		SingleAlertCatcher alertCatcher = new SingleAlertCatcher();
		serverHelper.server.setAlertHandler(alertCatcher);

		givenAnEstablishedSession(false);

		// WHEN sending a CLOSE_NOTIFY alert to the server
		client.send(alertToSend, establishedClientSession);

		// THEN assert that the server has removed all connection state with client
		AlertMessage alert = alertCatcher.waitForFirstAlert(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS);
		assertNotNull(alert);
		assertThat(serverHelper.serverConnectionStore.get(clientEndpoint), is(nullValue()));
	}

	/**
	 * Verify we send retransmission.
	 */
	@Test
	public void testRetransmission() throws Exception {
		// Configure UDP connector
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler();
		collector.applySession(null);
		UdpConnector rawClient = new UdpConnector(clientEndpoint.getPort(), collector);

		try {
			rawClient.start();
			clientEndpoint = rawClient.getAddress();

			// Send CLIENT_HELLO
			ClientHello clientHello = createClientHello();
			rawClient.sendRecord(serverHelper.serverEndpoint,
					DtlsTestTools.newDTLSRecord(ContentType.HANDSHAKE.getCode(), 0, 0, clientHello.toByteArray()));

			// Handle HELLO_VERIFY_REQUEST
			List<Record> rs = collector.waitForRecords(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS);
			assertNotNull("timeout", rs); // check there is no timeout
			Record record = rs.get(0);
			assertThat("Expected HANDSHAKE message from server", record.getType(), is(ContentType.HANDSHAKE));
			HandshakeMessage msg = (HandshakeMessage) record.getFragment();
			assertThat("Expected HELLO_VERIFY_REQUEST from server", msg.getMessageType(),
					is(HandshakeType.HELLO_VERIFY_REQUEST));
			Connection con = serverHelper.serverConnectionStore.get(clientEndpoint);
			assertNull(con);
			byte[] cookie = ((HelloVerifyRequest) msg).getCookie();
			assertNotNull(cookie);

			// Send CLIENT_HELLO with cookie
			clientHello.setCookie(cookie);
			rawClient.sendRecord(serverHelper.serverEndpoint,
					DtlsTestTools.newDTLSRecord(ContentType.HANDSHAKE.getCode(), 0, 0, clientHello.toByteArray()));

			// Handle SERVER HELLO
			// assert that we have an ongoingHandshake for this connection
			rs = collector.waitForRecords(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS);
			assertNotNull("timeout", rs); // check there is no timeout
			con = serverHelper.serverConnectionStore.get(clientEndpoint);
			assertNotNull(con);
			Handshaker ongoingHandshake = con.getOngoingHandshake();
			assertNotNull(ongoingHandshake);
			record = rs.get(0);
			assertThat("Expected HANDSHAKE message from server", record.getType(), is(ContentType.HANDSHAKE));
			msg = (HandshakeMessage) record.getFragment();
			assertThat("Expected SERVER_HELLO from server", msg.getMessageType(), is(HandshakeType.SERVER_HELLO));

			// Do not reply

			// Handle retransmission of SERVER HELLO
			rs = collector.waitForRecords(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS);
			assertNotNull("timeout", rs); // check there is no timeout
			con = serverHelper.serverConnectionStore.get(clientEndpoint);
			assertNotNull(con);
			ongoingHandshake = con.getOngoingHandshake();
			assertNotNull(ongoingHandshake);
			record = rs.get(0);
			assertThat("Expected HANDSHAKE message from server", record.getType(), is(ContentType.HANDSHAKE));
			msg = (HandshakeMessage) record.getFragment();
			assertThat("Expected SERVER_HELLO from server", msg.getMessageType(), is(HandshakeType.SERVER_HELLO));

		} finally {
			rawClient.stop();
		}
	}

	/**
	 * Verify we don't send retransmission after receiving the expected message.
	 */
	@Test
	public void testNoRetransmissionIfMessageReceived() throws Exception {
		// Configure UDP connector
		RecordCollectorDataHandler collector = new RecordCollectorDataHandler();
		collector.applySession(null);
		UdpConnector rawClient = new UdpConnector(clientEndpoint.getPort(), collector);

		// Add latency to PSK store
		pskStoreLatency = 1000;

		try {
			rawClient.start();
			clientEndpoint = rawClient.getAddress();

			// Send CLIENT_HELLO
			ClientHello clientHello = createClientHello(CipherSuite.TLS_PSK_WITH_AES_128_CCM_8);
			rawClient.sendRecord(serverHelper.serverEndpoint,
					DtlsTestTools.newDTLSRecord(ContentType.HANDSHAKE.getCode(), 0, 0, clientHello.toByteArray()));

			// Handle HELLO_VERIFY_REQUEST
			List<Record> rs = collector.waitForRecords(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS);
			assertNotNull("timeout", rs); // check there is no timeout
			Record record = rs.get(0);
			assertThat("Expected HANDSHAKE message from server", record.getType(), is(ContentType.HANDSHAKE));
			HandshakeMessage msg = (HandshakeMessage) record.getFragment();
			assertThat("Expected HELLO_VERIFY_REQUEST from server", msg.getMessageType(),
					is(HandshakeType.HELLO_VERIFY_REQUEST));
			Connection con = serverHelper.serverConnectionStore.get(clientEndpoint);
			assertNull(con);
			byte[] cookie = ((HelloVerifyRequest) msg).getCookie();
			assertNotNull(cookie);

			// Send CLIENT_HELLO with cookie
			clientHello.setCookie(cookie);
			rawClient.sendRecord(serverHelper.serverEndpoint,
					DtlsTestTools.newDTLSRecord(ContentType.HANDSHAKE.getCode(), 0, 0, clientHello.toByteArray()));

			// Handle SERVER HELLO
			// assert that we have an ongoingHandshake for this connection
			rs = collector.waitForRecords(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS);
			assertNotNull("timeout", rs); // check there is no timeout
			con = serverHelper.serverConnectionStore.get(clientEndpoint);
			assertNotNull(con);
			Handshaker ongoingHandshake = con.getOngoingHandshake();
			assertNotNull(ongoingHandshake);
			record = rs.get(0);
			assertThat("Expected HANDSHAKE message from server", record.getType(), is(ContentType.HANDSHAKE));
			msg = (HandshakeMessage) record.getFragment();
			assertThat("Expected SERVER_HELLO from server", msg.getMessageType(), is(HandshakeType.SERVER_HELLO));

			// Send CLIENT_KEY_EXCHANGE
			ClientKeyExchange keyExchange = new PSKClientKeyExchange(CLIENT_IDENTITY, serverHelper.serverEndpoint);
			keyExchange.setMessageSeq(clientHello.getMessageSeq() + 1);
			rawClient.sendRecord(serverHelper.serverEndpoint,
					DtlsTestTools.newDTLSRecord(ContentType.HANDSHAKE.getCode(), 0, 1, keyExchange.toByteArray()));

			// Ensure there is no retransmission
			assertNull(collector.waitForRecords((long) (serverHelper.serverConfig.getRetransmissionTimeout() * 1.1),
					TimeUnit.MILLISECONDS));
		} finally {
			rawClient.stop();
		}
	}

	/**
	 * Verifies behavior described in <a href="http://tools.ietf.org/html/rfc6347#section-4.2.8">
	 * section 4.2.8 of RFC 6347 (DTLS 1.2)</a>.
	 *  
	 * @throws Exception if test fails
	 */
	@Test
	public void testConnectorKeepsExistingSessionOnEpochZeroClientHello() throws Exception {

		givenAnEstablishedSession();

		// client has successfully established a secure session with server
		// and has been "crashed"
		// now we try to establish a new session with a client connecting from the
		// same IP address and port again
		RecordCollectorDataHandler handler = new RecordCollectorDataHandler();
		handler.applySession(null);

		UdpConnector rawClient = new UdpConnector(clientEndpoint.getPort(), handler);
		rawClient.start();

		rawClient.sendRecord(serverHelper.serverEndpoint,
				DtlsTestTools.newDTLSRecord(ContentType.HANDSHAKE.getCode(), 0, 0, createClientHello().toByteArray()));

		try {
			List<Record> flight = handler.assertFlight(1, MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS);
			Record record = flight.get(0);
			assertThat("Expected HANDSHAKE message from server",
					record.getType(), is(ContentType.HANDSHAKE));
			HandshakeMessage msg = (HandshakeMessage) record.getFragment();
			assertThat("Expected HELLO_VERIFY_REQUEST from server",
					msg.getMessageType(), is(HandshakeType.HELLO_VERIFY_REQUEST));
			Connection con = serverHelper.serverConnectionStore.get(clientEndpoint);
			assertNotNull(con);
			assertNotNull(con.getEstablishedSession());
			assertThat("Server should not have established new session with client yet",
					con.getEstablishedSession().getSessionIdentifier(),
					is(serverHelper.establishedServerSession.getSessionIdentifier()));
		} finally {
			synchronized (rawClient) {
				rawClient.stop();
				// give OS some time to release socket so that we can bind
				// original client to it again
				rawClient.wait(100);
			}
		}

		// now check if we can still use the originally established session to
		// exchange application data
		clientRawDataChannel.setLatchCount(1);
		// restart original client binding to same IP address and port as before
		client.restart();
		// make sure client still has original session in its cache
		Connection con = clientConnectionStore.get(serverHelper.serverEndpoint);
		assertNotNull(con);
		assertNotNull(con.getEstablishedSession());
		assertThat(con.getEstablishedSession().getSessionIdentifier(), is(serverHelper.establishedServerSession.getSessionIdentifier()));

		RawData data = RawData.outbound("Hello World".getBytes(), new AddressEndpointContext(serverHelper.serverEndpoint), null, false);
		client.send(data);

		con = serverHelper.serverConnectionStore.get(client.getAddress());
		assertNotNull(con);
		assertNotNull(con.getEstablishedSession());
		assertTrue(clientRawDataChannel.await(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));
		assertThat("Server should have reused existing session with client instead of creating a new one",
				con.getEstablishedSession().getSessionIdentifier(),
				is(serverHelper.establishedServerSession.getSessionIdentifier()));
	}

	/**
	 * Verifies behavior described in <a href="http://tools.ietf.org/html/rfc6347#section-4.2.8">
	 * section 4.2.8 of RFC 6347 (DTLS 1.2)</a>.
	 */
	@Test
	public void testAcceptClientHelloAfterIncompleteHandshake() throws Exception {
		if (client != null) {
			client.destroy();
		}
		// GIVEN a handshake that has been aborted before completion
		givenAnIncompleteHandshake();

		// WHEN starting a new handshake (epoch 0) reusing the same client IP
		clientConfig = newStandardConfig(clientEndpoint);
		clientConnectionStore = new InMemoryConnectionStore(CLIENT_CONNECTION_STORE_CAPACITY, 60);
		clientConnectionStore.setTag("client");
		client = new DTLSConnector(clientConfig, clientConnectionStore);

		// THEN assert that the handshake succeeds and a session is established
		// sometime the retransmission of the previous incomplete handshake
		// fails the new handshake. Therefore a retry is used.
		givenAnEstablishedSessionWithRetry();
	}

	/**
	 * Verifies behavior described in <a href="http://tools.ietf.org/html/rfc6347#section-4.2.8">
	 * section 4.2.8 of RFC 6347 (DTLS 1.2)</a>.
	 */
	@Test
	public void testClientHelloRetransmissionDoNotRestartHandshake() throws Exception {
		// configure UDP connector
		RecordCollectorDataHandler handler = new RecordCollectorDataHandler();
		handler.applySession(null);
		UdpConnector rawClient = new UdpConnector(clientEndpoint.getPort(), handler);
		try {
			rawClient.start();
			clientEndpoint = rawClient.getAddress();

			// send CLIENT_HELLO
			ClientHello clientHello = createClientHello();
			rawClient.sendRecord(
					serverHelper.serverEndpoint,
					DtlsTestTools.newDTLSRecord(ContentType.HANDSHAKE.getCode(), 0, 0, clientHello.toByteArray()));

			// handle HELLO_VERIFY_REQUEST
			List<Record> flight = handler.assertFlight(1, MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS);
			Record record = flight.get(0);

			assertThat("Expected HANDSHAKE message from server",
					record.getType(), is(ContentType.HANDSHAKE));
			HandshakeMessage msg = (HandshakeMessage) record.getFragment();
			assertThat("Expected HELLO_VERIFY_REQUEST from server",
					msg.getMessageType(), is(HandshakeType.HELLO_VERIFY_REQUEST));
			Connection con = serverHelper.serverConnectionStore.get(clientEndpoint);
			assertNull(con);
			byte[] cookie = ((HelloVerifyRequest) msg).getCookie();
			assertNotNull(cookie);

			// send CLIENT_HELLO with cookie
			clientHello.setCookie(cookie);
			rawClient.sendRecord(
					serverHelper.serverEndpoint,
					DtlsTestTools.newDTLSRecord(ContentType.HANDSHAKE.getCode(), 0, 0, clientHello.toByteArray()));

			// assert that we have an ongoingHandshake for this connection
			flight = handler.assertFlight(1, MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS);
			record = flight.get(0);

			assertThat("Expected HANDSHAKE message from server",
					record.getType(), is(ContentType.HANDSHAKE));
			msg = (HandshakeMessage) record.getFragment();
			assertThat("Expected SERVER_HELLO from server",
					msg.getMessageType(), is(HandshakeType.SERVER_HELLO));

			con = serverHelper.serverConnectionStore.get(clientEndpoint);
			assertNotNull(con);
			Handshaker ongoingHandshake = con.getOngoingHandshake();
			assertNotNull(ongoingHandshake);

			// send CLIENT_KEY_EXCHANGE
			ClientKeyExchange keyExchange = new PSKClientKeyExchange(CLIENT_IDENTITY, serverHelper.serverEndpoint);
			rawClient.sendRecord(serverHelper.serverEndpoint,
							DtlsTestTools.newDTLSRecord(ContentType.HANDSHAKE.getCode(), 0, 1, keyExchange.toByteArray()));
			handler.assertFlight(1, MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS);

			// re-send CLIENT_HELLO to simulate retransmission
			rawClient.sendRecord(
					serverHelper.serverEndpoint,
					DtlsTestTools.newDTLSRecord(ContentType.HANDSHAKE.getCode(), 0, 0, clientHello.toByteArray()));

			// send Alert to receive an answer even
			AlertMessage closeNotify = new AlertMessage(AlertLevel.FATAL, AlertDescription.CLOSE_NOTIFY, serverHelper.serverEndpoint);
			rawClient.sendRecord(serverHelper.serverEndpoint, DtlsTestTools.newDTLSRecord(ContentType.ALERT.getCode(), 0, 2, closeNotify.toByteArray()));

			// check that we don't get a response for this CLIENT_HELLO, it must be ignore
			flight = handler.assertFlight(1, MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS);

			record = flight.get(0);
			assertThat("Expected ALERT message from server",
					record.getType(), is(ContentType.ALERT));

		} finally {
			rawClient.stop();
		}
	}

	/**
	 * Verifies behavior described in <a href="http://tools.ietf.org/html/rfc6347#section-4.2.8">
	 * section 4.2.8 of RFC 6347 (DTLS 1.2)</a>.
	 *  
	 * @throws Exception if test fails
	 */
	@Test
	public void testConnectorReplacesExistingSessionAfterFullHandshake() throws Exception {

		givenAnEstablishedSession();

		// the ID of the originally established session
		SessionId originalSessionId = serverHelper.establishedServerSession.getSessionIdentifier();

		// client has successfully established a secure session with server
		// and has been "crashed"
		// now we try to establish a new session with a client connecting from the
		// same IP address and port again
		clientRawDataChannel.setLatchCount(1);
		client = new DTLSConnector(newStandardConfig(clientEndpoint));
		client.setRawDataReceiver(clientRawDataChannel);
		client.setExecutor(executor);
		client.start();

		RawData data = RawData.outbound("Hello World".getBytes(), new AddressEndpointContext(serverHelper.serverEndpoint), null, false);
		client.send(data);

		assertTrue(clientRawDataChannel.await(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));
		Connection con = serverHelper.serverConnectionStore.get(clientEndpoint);
		assertNotNull(con);
		assertNotNull(con.getEstablishedSession());
		// make sure that the server has created a new session replacing the original one with the client
		assertThat("Server should have replaced original session with client with a newly established one",
				con.getEstablishedSession().getSessionIdentifier(), is(not(originalSessionId)));
	}


	@Test
	public void testStartStopWithNewAddress() throws Exception {
		// Do a first handshake
		givenAnEstablishedSession(false);
		byte[] sessionId = serverHelper.establishedServerSession.getSessionIdentifier().getBytes();
		InetSocketAddress firstAddress = client.getAddress();

		// Stop the client
		client.stop();
		Connection connection = clientConnectionStore.get(serverHelper.serverEndpoint);
		assertArrayEquals(sessionId, connection.getEstablishedSession().getSessionIdentifier().getBytes());

		// Restart it
		client.start();
		assertNotEquals(firstAddress,client.getAddress());

		// Prepare message sending
		final String msg = "Hello Again";
		clientRawDataChannel.setLatchCount(1);

		// send message
		RawData data = RawData.outbound(msg.getBytes(), new AddressEndpointContext(serverHelper.serverEndpoint), null, false);
		client.send(data);
		assertTrue(clientRawDataChannel.await(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));

		// check we use the same session id
		connection = clientConnectionStore.get(serverHelper.serverEndpoint);
		assertArrayEquals(sessionId, connection.getEstablishedSession().getSessionIdentifier().getBytes());
		assertClientIdentity(RawPublicKeyIdentity.class);
	}

	@Test
	public void testStartStopWithSameAddress() throws Exception {
		// Do a first handshake
		givenAnEstablishedSession(false);
		byte[] sessionId = serverHelper.establishedServerSession.getSessionIdentifier().getBytes();
		InetSocketAddress firstAddress = client.getAddress();

		// Stop the client
		client.stop();
		Connection connection = clientConnectionStore.get(serverHelper.serverEndpoint);
		assertArrayEquals(sessionId, connection.getEstablishedSession().getSessionIdentifier().getBytes());

		// Restart it
		client.restart();
		assertEquals(firstAddress, client.getAddress());

		// Prepare message sending
		final String msg = "Hello Again";
		clientRawDataChannel.setLatchCount(1);

		// send message
		RawData data = RawData.outbound(msg.getBytes(), new AddressEndpointContext(serverHelper.serverEndpoint), null, false);
		client.send(data);
		assertTrue(clientRawDataChannel.await(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));

		// check we use the same session id
		connection = clientConnectionStore.get(serverHelper.serverEndpoint);
		assertArrayEquals(sessionId, connection.getEstablishedSession().getSessionIdentifier().getBytes());
		assertClientIdentity(RawPublicKeyIdentity.class);
	}

	@Test
	public void testStartStopWithSameAddressAndInternalExecutor() throws Exception {
		// use internal executor
		client.setExecutor(null);
		// Do a first handshake
		givenAnEstablishedSession(false);
		byte[] sessionId = serverHelper.establishedServerSession.getSessionIdentifier().getBytes();
		InetSocketAddress firstAddress = client.getAddress();

		// Stop the client
		client.stop();
		Connection connection = clientConnectionStore.get(serverHelper.serverEndpoint);
		assertArrayEquals(sessionId, connection.getEstablishedSession().getSessionIdentifier().getBytes());

		// Restart it
		client.restart();
		assertEquals(firstAddress, client.getAddress());

		// Prepare message sending
		final String msg = "Hello Again";
		clientRawDataChannel.setLatchCount(1);

		// send message
		RawData data = RawData.outbound(msg.getBytes(), new AddressEndpointContext(serverHelper.serverEndpoint), null, false);
		client.send(data);
		assertTrue(clientRawDataChannel.await(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS));

		// check we use the same session id
		connection = clientConnectionStore.get(serverHelper.serverEndpoint);
		assertArrayEquals(sessionId, connection.getEstablishedSession().getSessionIdentifier().getBytes());
		assertClientIdentity(RawPublicKeyIdentity.class);
	}

	@Test
	public void testConnectorSendsHelloVerifyRequestWithoutCreatingSession() throws Exception {

		InetSocketAddress endpoint = new InetSocketAddress(12000);
		RecordCollectorDataHandler handler = new RecordCollectorDataHandler();
		handler.applySession(null);
		UdpConnector rawClient = new UdpConnector(endpoint.getPort(), handler);

		try{
			rawClient.start();
	
			// send a CLIENT_HELLO without cookie
			ClientHello clientHello = createClientHello();
	
			rawClient.sendRecord(serverHelper.serverEndpoint,
					DtlsTestTools.newDTLSRecord(ContentType.HANDSHAKE.getCode(), 0, 0, clientHello.toByteArray()));

			List<Record> flight = handler.assertFlight(1, MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS);
			Record record = flight.get(0);

			Assert.assertThat("Expected HANDSHAKE message from server",
					record.getType(), is(ContentType.HANDSHAKE));
			HandshakeMessage handshake = (HandshakeMessage) record.getFragment();
			Assert.assertThat("Expected HELLO_VERIFY_REQUEST from server",
					handshake.getMessageType(), is(HandshakeType.HELLO_VERIFY_REQUEST));
			Assert.assertNull("Server should not have created session for CLIENT_HELLO containging no cookie",
					serverHelper.serverConnectionStore.get(endpoint));
		} finally {
			rawClient.stop();
		}
	}

	@Test
	public void testConnectorAcceptsClientHelloAfterLostHelloVerifyRequest() throws Exception {

		// make the server send a HELLO_VERIFY_REQUEST
		testConnectorSendsHelloVerifyRequestWithoutCreatingSession();
		// ignore the HELLO_VERIFY_REQUEST (i.e. assume the request is lost)
		// and try to establish a fresh session
		givenAnEstablishedSession();
		Assert.assertThat(serverHelper.establishedServerSession.getPeer(), is(clientEndpoint));
	}

	@Test
	public void testConnectorTerminatesHandshakeIfConnectionStoreIsExhausted() throws Exception {
		serverHelper.serverConnectionStore.clear();
		assertEquals(SERVER_CONNECTION_STORE_CAPACITY, serverHelper.serverConnectionStore.remainingCapacity());
		assertTrue(serverHelper.serverConnectionStore.put(new Connection(new InetSocketAddress("192.168.0.1", 5050), new SerialExecutor(executor))));
		assertTrue(serverHelper.serverConnectionStore.put(new Connection(new InetSocketAddress("192.168.0.2", 5050), new SerialExecutor(executor))));
		assertTrue(serverHelper.serverConnectionStore.put(new Connection(new InetSocketAddress("192.168.0.3", 5050), new SerialExecutor(executor))));

		clientRawDataChannel.setLatchCount(1);
		client.setRawDataReceiver(clientRawDataChannel);
		client.start();
		clientEndpoint = client.getAddress();
		RawData data = RawData.outbound("Hello World".getBytes(), new AddressEndpointContext(serverHelper.serverEndpoint), null, false);
		client.send(data);

		assertFalse(clientRawDataChannel.await(500, TimeUnit.MILLISECONDS));
		assertNull("Server should not have established a session with client", serverHelper.serverConnectionStore.get(clientEndpoint));
	}

	/**
	 * Verifies that connector ignores CLIENT KEY EXCHANGE with unknown PSK identity.
	 */
	@Test
	public void testConnectorIgnoresUnknownPskIdentity() throws Exception {
		ensureConnectorIgnoresBadCredentials(new StaticPskStore("unknownIdentity", CLIENT_IDENTITY_SECRET.getBytes()));
	}

	/**
	 * Verifies that connector ignores FINISHED message with bad PSK.
	 */
	@Test
	public void testConnectorIgnoresBadPsk() throws Exception {
		ensureConnectorIgnoresBadCredentials(new StaticPskStore(CLIENT_IDENTITY, "bad_psk".getBytes()));
	}

	private void ensureConnectorIgnoresBadCredentials(PskStore pskStoreWithBadCredentials) throws Exception {
		if (client != null) {
			client.destroy();
		}
		clientConfig = new DtlsConnectorConfig.Builder()
			.setLoggingTag("client")
			.setAddress(clientEndpoint)
			.setPskStore(pskStoreWithBadCredentials)
			.setRetransmissionTimeout(500)
			.setMaxRetransmissions(2)
			.build();
		client = serverHelper.createClient(clientConfig);
		client.start();
		SimpleMessageCallback callback = new SimpleMessageCallback();
		RawData data = RawData.outbound("Hello".getBytes(), new AddressEndpointContext(serverHelper.serverEndpoint), callback, false);
		client.send(data);

		Throwable error = callback.getError(TimeUnit.SECONDS.toMillis(MAX_TIME_TO_WAIT_SECS * 5));
		assertThat(error, is(notNullValue()));
		// timeout is not reported with HandshakeException!
		assertThat(error, not(instanceOf(HandshakeException.class)));

		LatchSessionListener listener = serverHelper.sessionListenerMap.get(client.getAddress());
		assertThat("server side session listener missing", listener, is(notNullValue()));
		Throwable cause = listener.waitForSessionFailed(4000, TimeUnit.MILLISECONDS);
		assertThat("server side handshake failure missing", cause, is(notNullValue()));
		assertThat(cause.getMessage(), containsString("handshake flight"));

		listener = serverHelper.sessionListenerMap.get(serverHelper.serverEndpoint);
		assertThat("client side session listener missing", listener, is(notNullValue()));
		cause = listener.waitForSessionFailed(4000, TimeUnit.MILLISECONDS);
		assertThat("client side handshake failure missing", cause, is(notNullValue()));
		assertThat(cause.getMessage(), containsString("handshake flight "));
	}

	@Test
	public void testProcessApplicationUsesNullPrincipalForUnauthenticatedPeer() throws Exception {
		ConnectorHelper serverHelper = new ConnectorHelper();
		try {
			// given an established session with a server that doesn't require
			// clients to authenticate
			DtlsConnectorConfig.Builder serverConfig = new DtlsConnectorConfig.Builder()
					.setAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
					.setLoggingTag("server")
					.setIdentity(DtlsTestTools.getPrivateKey(), DtlsTestTools.getServerCertificateChain(), CertificateType.RAW_PUBLIC_KEY)
					.setClientAuthenticationRequired(false);
			serverHelper.startServer(serverConfig);
			serverHelper.givenAnEstablishedSession(client);
			assertThat(serverHelper.serverRawDataProcessor.getClientEndpointContext().getPeerIdentity(), is(nullValue()));
		} finally {
			serverHelper.destroyServer();
		}
	}

	private void assertClientIdentity(final Class<?> principalType) {

		// assert that client identity is of given type
		assertThat(serverHelper.serverRawDataProcessor.getClientEndpointContext().getPeerIdentity(), instanceOf(principalType));
	}

	@Test
	public void testGetMaximumTransmissionUnitReturnsDefaultValue() {
		// given a connector that has not been started yet
		DTLSConnector connector = client;

		// when retrieving the maximum transmission unit from the client
		int mtu = connector.getMaximumTransmissionUnit();

		// then the value is the IPv4 min. MTU
		assertThat(mtu, is(DTLSConnector.DEFAULT_IPV4_MTU));
	}

	@Test
	public void testGetMaximumFragmentLengthReturnsDefaultValueForUnknownPeer() {
		// given an empty client side connection store
		clientConnectionStore.clear();

		// when querying the max fragment length for an unknown peer
		InetSocketAddress unknownPeer = serverHelper.serverEndpoint;
		int maxFragmentLength = client.getMaximumFragmentLength(unknownPeer);

		// then the value is the minimum IPv4 MTU - DTLS/UDP/IP header overhead
		assertThat(maxFragmentLength, is(DTLSConnector.DEFAULT_IPV4_MTU - DTLSSession.HEADER_LENGTH));
	}

	@Test
	public void testDestroyClearsConnectionStore() throws Exception {
		// given a non-empty connection store
		givenAnEstablishedSession();
		assertThat(clientConnectionStore.get(serverHelper.serverEndpoint), is(notNullValue()));

		// when the client connector is destroyed
		client.destroy();

		// assert that the client's connection store is empty
		assertThat(clientConnectionStore.remainingCapacity(), is(CLIENT_CONNECTION_STORE_CAPACITY));
		assertThat(clientConnectionStore.get(serverHelper.serverEndpoint), is(nullValue()));
	}

	@Test
	public void testNoRenegotiationAllowed() throws Exception {
		givenAnEstablishedSession(false);
		
		// Catch alert receive by the client
		SingleAlertCatcher alertCatcher = new SingleAlertCatcher();
		client.setAlertHandler(alertCatcher);
		
		// send a CLIENT_HELLO message to the server to renegotiation connection
		client.sendRecord(new Record(ContentType.HANDSHAKE, establishedClientSession.getWriteEpoch(),
				establishedClientSession.getSequenceNumber(), createClientHello(),
				establishedClientSession, false, 0));

		// ensure server answer with a NO_RENOGIATION alert
		AlertMessage alert = alertCatcher.waitForFirstAlert(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS);
		assertNotNull("Client does not receive alert as answer to renenotiation", alert);
		assertEquals("Server must answer with a NO_RENEGOTIATION alert", AlertDescription.NO_RENEGOTIATION, alert.getDescription());
		assertEquals("NO_RENEGOTIATION alert MUST be a warning", AlertLevel.WARNING, alert.getLevel());
	}

	@Test
	public void testNoRenegotiationOnHelloRequest() throws Exception {
		givenAnEstablishedSession(false);
		
		// Catch alert receive by the server
		SingleAlertCatcher alertCatcher = new SingleAlertCatcher();
		serverHelper.server.setAlertHandler(alertCatcher);
		
		// send a HELLO_REQUEST message to the client
		serverHelper.server.sendRecord(new Record(ContentType.HANDSHAKE, serverHelper.establishedServerSession.getWriteEpoch(),
				serverHelper.establishedServerSession.getSequenceNumber(), new HelloRequest(clientEndpoint),
				serverHelper.establishedServerSession, false, 0));

		// ensure client answer with a NO_RENOGIATION alert
		AlertMessage alert = alertCatcher.waitForFirstAlert(MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS);
		assertNotNull("Server does not receive alert as answer of HELLO_REQUEST", alert);
		assertEquals("Client must answer to HELLO_REQUEST with a NO_RENEGOTIATION alert", AlertDescription.NO_RENEGOTIATION, alert.getDescription());
		assertEquals("NO_RENEGOTIATION alert MUST be a warning", AlertLevel.WARNING, alert.getLevel());
	}

	/**
	 * Test invoking of onConnect when sending without session.
	 * Test onConnect is not invoked, when sending with established session.
	 */
	@Test
	public void testSendingInvokesOnConnect() throws Exception {
		// GIVEN a EndpointContextMatcher, blocking
		SimpleMessageCallback callback = new SimpleMessageCallback(1, false);
		// GIVEN a message to send
		RawData outboundMessage = RawData.outbound(new byte[] { 0x01 },
				new AddressEndpointContext(serverHelper.serverEndpoint), callback, false);
		client.start();

		// WHEN sending the initial message
		client.send(outboundMessage);

		// THEN assert that a session is established.
		assertThat(callback.await(TimeUnit.SECONDS.toMillis(MAX_TIME_TO_WAIT_SECS)), is(true));

		// THEN assert that onConnect is invoked once
		assertThat(callback.isConnecting(), is(true));

		// WHEN sending the next message
		callback = new SimpleMessageCallback(1, false);
		// GIVEN a message to send
		outboundMessage = RawData.outbound(new byte[] { 0x01 }, new AddressEndpointContext(serverHelper.serverEndpoint),
				callback, false);
		client.send(outboundMessage);

		assertThat(callback.await(TimeUnit.SECONDS.toMillis(MAX_TIME_TO_WAIT_SECS)), is(true));
		// THEN assert that onConnect is not invoked
		assertThat(callback.isConnecting(), is(false));
	}

	private ClientHello createClientHello(CipherSuite... cipherSuites) {
		List<CipherSuite> list = clientConfig.getSupportedCipherSuites();
		if (cipherSuites != null && cipherSuites.length > 0) {
			list = Arrays.asList(cipherSuites);
		}
		ClientHello hello = new ClientHello(new ProtocolVersion(), list,
				clientConfig.getIdentityCertificateTypes(), clientConfig.getTrustCertificateTypes(), clientEndpoint);
		hello.addCompressionMethod(CompressionMethod.NULL);
		hello.setMessageSeq(0);
		return hello;
	}

	private void givenAnEstablishedSession() throws Exception {
		givenAnEstablishedSession(true);
	}

	private void givenAnEstablishedSession(boolean releaseSocket) throws Exception {
		RawData raw = RawData.outbound("Hello World".getBytes(), new AddressEndpointContext(serverHelper.serverEndpoint), null, false);
		givenAnEstablishedSession(raw, releaseSocket);
	}

	private void givenAnEstablishedSession(RawData msgToSend, boolean releaseSocket) throws Exception {

		clientRawDataChannel = serverHelper.givenAnEstablishedSession(client, msgToSend, false);
		clientEndpoint = client.getAddress();
		Connection con = clientConnectionStore.get(serverHelper.serverEndpoint);
		assertNotNull(con);
		establishedClientSession = con.getEstablishedSession();
		assertNotNull(establishedClientSession);
		if (releaseSocket) {
			client.stop();
		}
	}

	private void givenAnEstablishedSessionWithRetry() throws Exception {
		SimpleMessageCallback callback = new SimpleMessageCallback();
		RawData raw = RawData.outbound("Hello World".getBytes(),
				new AddressEndpointContext(serverHelper.serverEndpoint), callback, false);
		try {
			clientRawDataChannel = serverHelper.givenAnEstablishedSession(client, raw, false);
		} catch (AssertionError error) {
			Throwable sendError = callback.getError();
			if (sendError instanceof HandshakeException) {
				// sending failed, retry
				raw = RawData.outbound("Hello World".getBytes(),
						new AddressEndpointContext(serverHelper.serverEndpoint), callback, false);
				client.stop();
				clientRawDataChannel = serverHelper.givenAnEstablishedSession(client, raw, false);
			} else {
				if (sendError != null) {
					sendError.printStackTrace();
				}
				throw error;
			}
		}
		clientEndpoint = client.getAddress();
		Connection con = clientConnectionStore.get(serverHelper.serverEndpoint);
		assertNotNull(con);
		establishedClientSession = con.getEstablishedSession();
		assertNotNull(establishedClientSession);
		client.stop();
	}

	private void givenAnIncompleteHandshake() throws Exception {
		// configure UDP connector
		RecordCollectorDataHandler handler = new RecordCollectorDataHandler();
		handler.applySession(null);
		UdpConnector rawClient = new UdpConnector(clientEndpoint.getPort(), handler);

		try {
			rawClient.start();
			clientEndpoint = rawClient.getAddress();

			// send CLIENT_HELLO
			ClientHello clientHello = createClientHello();
			rawClient.sendRecord(
					serverHelper.serverEndpoint,
					DtlsTestTools.newDTLSRecord(ContentType.HANDSHAKE.getCode(), 0, 0, clientHello.toByteArray()));

			// handle HELLO_VERIFY_REQUEST
			List<Record> flight = handler.assertFlight(1, MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS);
			Record record = flight.get(0);
			assertThat("Expected HANDSHAKE message from server",
					record.getType(), is(ContentType.HANDSHAKE));
			HandshakeMessage msg = (HandshakeMessage) record.getFragment();
			assertThat("Expected HELLO_VERIFY_REQUEST from server",
					msg.getMessageType(), is(HandshakeType.HELLO_VERIFY_REQUEST));
			Connection con = serverHelper.serverConnectionStore.get(clientEndpoint);
			assertNull(con);
			byte[] cookie = ((HelloVerifyRequest) msg).getCookie();
			assertNotNull(cookie);

			// send CLIENT_HELLO with cookie
			clientHello.setCookie(cookie);
			rawClient.sendRecord(
					serverHelper.serverEndpoint,
					DtlsTestTools.newDTLSRecord(ContentType.HANDSHAKE.getCode(), 0, 0, clientHello.toByteArray()));

			handler.assertFlight(1, MAX_TIME_TO_WAIT_SECS, TimeUnit.SECONDS);

			// assert that we have an ongoingHandshake for this connection
			con = serverHelper.serverConnectionStore.get(clientEndpoint);
			assertNotNull(con);
			Handshaker ongoingHandshake = con.getOngoingHandshake();
			assertNotNull(ongoingHandshake);
		} finally {
			synchronized (rawClient) {
				rawClient.stop();
				// in order to prevent sporadic BindExceptions during test execution
				// give OS some time before allowing test cases to re-bind to same port
				rawClient.wait(200);
			}
		}
	}

	private static class SingleAlertCatcher implements AlertHandler {

		private CountDownLatch latch = new CountDownLatch(1);
		private AlertMessage alert;

		@Override
		public void onAlert(InetSocketAddress peer, AlertMessage alert) {
			if (latch.getCount() != 0) {
				this.alert = alert;
				latch.countDown();
			}
		}

		/**
		 * @return {@code AlertMessage} if the count reached zero and {@code n}
		 *         if the waiting time elapsed before the count reached zero
		 */
		public AlertMessage waitForFirstAlert(long timeout, TimeUnit unit) throws InterruptedException {
			if (latch.await(timeout, unit)) {
				return alert;
			} else {
				return null;
			}
		}
	}
}
