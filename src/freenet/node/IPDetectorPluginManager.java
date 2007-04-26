package freenet.node;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Vector;

import freenet.io.comm.Peer;
import freenet.node.useralerts.ProxyUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.pluginmanager.DetectedIP;
import freenet.pluginmanager.FredPluginIPDetector;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.OOMHandler;
import freenet.support.transport.ip.IPUtil;

/**
 * Tracks all known IP address detection plugins, and runs them when appropriate.
 * Normally there would only be one, but sometimes there may be more than one.
 */
public class IPDetectorPluginManager {
	
	public class MyUserAlert implements UserAlert {

		final short code;
		final boolean suggestPortForward;
		final String text;
		final String title;
		private boolean isValid = true;
		
		public MyUserAlert(String title, String text, boolean suggestPortForward, short code) {
			this.title = title;
			this.text = text;
			this.suggestPortForward = suggestPortForward;
			this.code = code;
		}

		public String dismissButtonText() {
			return "Hide";
		}

		public HTMLNode getHTMLText() {
			HTMLNode div = new HTMLNode("div");
			div.addChild("#", text);
			if(suggestPortForward) {
				div.addChild("#", " You may want to ");
				div.addChild("a", "href", "/?_CHECKED_HTTP_=http://wiki.freenetproject.org/FirewallAndRouterIssues", "forward the port");
				div.addChild("#", " (UDP port number "+node.portNumber+") manually (or you may already have done so, Freenet cannot easily detect this).");
			}
			return div;
		}

		public short getPriorityClass() {
			return code;
		}

		public String getText() {
			if(!suggestPortForward) return text;
			StringBuffer sb = new StringBuffer();
			sb.append(text);
			sb.append(" You may want to forward the port (UDP port number "+node.portNumber+") manually. (See http://wiki.freenetproject.org/FirewallAndRouterIssues ).");
			return sb.toString();
		}

		public String getTitle() {
			return title;
		}

		public boolean isValid() {
			return isValid;
		}

		public void isValid(boolean validity) {
			isValid = validity;
		}

		public void onDismiss() {
			isValid = false;
		}

		public boolean shouldUnregisterOnDismiss() {
			return false;
		}

		public boolean userCanDismiss() {
			return !suggestPortForward;
		}

	}

	static boolean logMINOR;
	private final NodeIPDetector detector;
	private final Ticker ticker;
	private final Node node;
	FredPluginIPDetector[] plugins;
	private final MyUserAlert noConnectionAlert;
	private final MyUserAlert symmetricAlert;
	private final MyUserAlert portRestrictedAlert;
	private final MyUserAlert restrictedAlert;
	private final MyUserAlert fullConeAlert;
	private final MyUserAlert connectedAlert;
	private ProxyUserAlert proxyAlert;
	
	IPDetectorPluginManager(Node node, NodeIPDetector detector) {
		logMINOR = Logger.shouldLog(Logger.MINOR, getClass());
		plugins = new FredPluginIPDetector[0];
		this.node = node;
		this.ticker = node.ps;
		this.detector = detector;
		noConnectionAlert = new MyUserAlert("No UDP connectivity",
				"Your internet connection does not appear to support UDP. " +
				"Unless this detection is wrong, it is unlikely that Freenet will work on your computer at present.",
				true, UserAlert.ERROR);
		symmetricAlert = new MyUserAlert("Symmetric firewall detected",
				"Your internet connection appears to be behind a symmetric NAT or firewall. " +
				"You will probably only be able to connect to users directly connected to the internet or behind " +
				"restricted cone NATs.", true, UserAlert.ERROR);				
		portRestrictedAlert = new MyUserAlert("Port restricted cone NAT detected",
				"Your internet connection appears to be behind a port-restricted NAT (router). "+
				"You will be able to connect to most other users, but not those behind symmetric NATs.", 
				true, UserAlert.WARNING);
		restrictedAlert = new MyUserAlert("Restricted cone NAT detected",
				"Your internet connection appears to be behind a \"restricted cone\" NAT (router). "+
				"You should be able to connect to most other users.", false, UserAlert.MINOR);
		fullConeAlert = new MyUserAlert("Full cone NAT detected",
				"Your internet connection appears to be behind a \"full cone\" NAT (router). Congratulations, your node " +
				"should be able to connect to any other Freenet node.", false, UserAlert.MINOR);
		connectedAlert = new MyUserAlert("Direct internet connection detected",
				"You appear to be directly connected to the internet. Congratulations, you should be able to connect "+
				"to any other freenet node.", false, UserAlert.MINOR);
	}

	/** Start the detector plugin manager. This includes running the plugin, if there
	 * is one, and if it is necessary to do so. */
	void start() {
		// Cannot be initialized until UserAlertManager has been created.
		proxyAlert = new ProxyUserAlert(node.clientCore.alerts);
		tryMaybeRun();
	}
	
	/**
	 * Start the plugin detection, if necessary. Either way, schedule another attempt in
	 * 1 minute's time.
	 */
	private void tryMaybeRun() {
		try {
			maybeRun();
		} catch (Throwable t) {
			Logger.error(this, "Caught "+t, t);
		}
		ticker.queueTimedJob(new Runnable() {
			public void run() {
				tryMaybeRun();
			}
		}, 60*1000);
	}

	/**
	 * Register a plugin.
	 */
	public void register(FredPluginIPDetector d) {
		if(d == null) throw new NullPointerException();
		synchronized(this) {
			FredPluginIPDetector[] newPlugins = new FredPluginIPDetector[plugins.length+1];
			System.arraycopy(plugins, 0, newPlugins, 0, plugins.length);
			newPlugins[plugins.length] = d;
			plugins = newPlugins;
		}
		maybeRun();
	}

	/**
	 * Remove a plugin.
	 */
	public void remove(FredPluginIPDetector d) {
		synchronized(this) {
			int count = 0;
			for(int i=0;i<plugins.length;i++) {
				if(plugins[i] == d) count++;
			}
			if(count == 0) return;
			FredPluginIPDetector[] newPlugins = new FredPluginIPDetector[plugins.length - count];
			int x = 0;
			for(int i=0;i<plugins.length;i++) {
				if(newPlugins[i] != d) newPlugins[x++] = plugins[i];
			}
			plugins = newPlugins;
		}
	}

	
	/* When should we run an IP address detection? This is for things like STUN, so
	 * there may conceivably be some exposure or risk, or limited resources, so not 
	 * all the time.
	 * 
	 * If we don't get a real IP address from a detection, we should not run another
	 * one for 5 minutes. This indicated that we were not on the internet *at all*.
	 * 
	 * If we have a directly detected IP, and:
	 * - We have no peers older than 30 minutes OR
	 * - We have successfully connected to two different peers with different real 
	 *   internet addresses to us since startup
	 *
	 * Then we should not run a detection. (However, we don't entirely exclude it 
	 * because we may be behind a firewall).
	 * 
	 * If we have no peers, and we haven't run a detection in the last 6 hours (don't
	 * save this time over startups), we should run a detection.
	 * 
	 * Otherwise, we have peers, and if we have run a detection in the last hour we 
	 * should not run another one.
	 * 
	 * If we have one or two connected peers, both of which report the same IP 
	 * address, and we have other nodes which have been connected recently, and this 
	 * state has persisted for 2 minutes, we should run a detection.
	 * (To protect against bogus IP address reports)
	 * 
	 * If we have no connected peers with real internet addresses, and this state has
	 * persisted for 2 minutes, and we have disconnected peers, then we should run a 
	 * detection. (every hour that we are down)
	 * (To detect new IP address)
	 */ 
	
	private DetectorRunner runner;
	private boolean lastDetectAttemptFailed;
	private long lastDetectAttemptEndedTime;
	private long firstTimeMaybeFakePeers;
	private long firstTimeUrgent;
	
	/**
	 * Do we need to run a plugin?
	 */
	public void maybeRun() {
		logMINOR = Logger.shouldLog(Logger.MINOR, getClass());
		if(logMINOR) Logger.minor(this, "Maybe running IP detection plugins", new Exception("debug"));
		PeerNode[] peers = node.getPeerNodes();
		PeerNode[] conns = node.getConnectedPeers();
		Peer[] nodeAddrs = detector.getPrimaryIPAddress();
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(plugins.length == 0) {
				if(logMINOR) Logger.minor(this, "No IP detection plugins");
				detector.hasDetectedPM();
				return;
			}
			if(runner != null) {
				if(logMINOR) Logger.minor(this, "Already running IP detection plugins");
				return;
			}
			// If detect attempt failed to produce an IP in the last 5 minutes, don't
			// try again yet.
			if(lastDetectAttemptFailed) {
				if(now - lastDetectAttemptEndedTime < 5*60*1000) {
					if(logMINOR) Logger.minor(this, "Last detect failed and more than 5 minutes ago");
					return;
				} else {
					startDetect();
				}
			}
			if(detector.hasDirectlyDetectedIP()) {
				// We might still be firewalled?
				// First, check only once per day or startup
				if(now - lastDetectAttemptEndedTime < 12*60*60*1000) {
					if(logMINOR) Logger.minor(this, "Node has directly detected IP and we have checked less than 12 hours ago");
					return;
				}
				
				// Now, if we have two nodes with unique IPs which aren't ours
				// connected, we don't need to detect.
				HashSet addressesConnected = null;
				boolean hasOldPeers = false;
				for(int i=0;i<peers.length;i++) {
					PeerNode p = peers[i];
					if(p.isConnected() || (now - p.lastReceivedPacketTime() < 24*60*60*1000)) {
						// Has been connected in the last 24 hours.
						// Unique IP address?
						Peer peer = p.getPeer();
						if(peer != null){
							InetAddress addr = peer.getAddress(false);
							if(p.isConnected() && (addr != null) && IPUtil.isValidAddress(peer.getAddress(), false)) {
								// Connected node, on a real internet IP address.
								// Is it internal?
								boolean internal = false;
								for(int j=0;j<nodeAddrs.length;j++) {
									if(addr.equals(nodeAddrs[j].getAddress())) {
										// Internal
										internal = true;
										break;
									}
								}
								if(!internal) {
									// Real IP address
									if(addressesConnected == null)
										addressesConnected = new HashSet();
									addressesConnected.add(addr);
									if(addressesConnected.size() > 2) {
										// 3 connected addresses, lets assume we have connectivity.
										if(logMINOR) Logger.minor(this, "Node has directly detected IP and has connected to 3 real IPs");
										return;
									}
								}
							}
						}
						long l = p.getPeerAddedTime();
						if((l <= 0) || (now - l > 30*60*1000)) {
							hasOldPeers = true;
						}
					}
				}
				if(!hasOldPeers) {
					// No peers older than 30 minutes
					if(logMINOR) Logger.minor(this, "Not detecting as less than 30 minutes old");
					return;
				}
			}
			
			if(peers.length == 0) {
				if(now - lastDetectAttemptEndedTime < 6*60*60*1000) {
					// No peers, only try every 6 hours.
					if(logMINOR) Logger.minor(this, "No peers but detected less than 6 hours ago");
					return;
				} else {
					// Must try once!
					startDetect();
					return;
				}
			} else {
				
				boolean detect = false;
				
				// If we have no connections, and several disconnected but enabled 
				// peers, then run a detection.
				
				boolean maybeUrgent = false;
				
				if(conns.length == 0) {
					
					// No connections.
					for(int i=0;i<peers.length;i++) {
						PeerNode p = peers[i];
						if(!p.isDisabled()) {
							maybeUrgent = true;
							if(logMINOR) Logger.minor(this, "No connections, but have peers, may detect...");
							break;
						}
					}
				}
				
				if(detector.maybeSymmetric && lastDetectAttemptEndedTime <= 0) // If it appears to be an SNAT, do a detection at least once
					maybeUrgent = true;
				
				if(maybeUrgent) {
					if(firstTimeUrgent <= 0)
						firstTimeUrgent = now;
					
					if(now - firstTimeUrgent > 2*60*1000)
						detect = true;
					
					if(!(detector.oldIPAddress != null && detector.oldIPAddress.isRealInternetAddress(false, false)))
						detect = true; // else wait 2 minutes
					
				} else {
					if(logMINOR) Logger.minor(this, "Not urgent; conns="+conns.length);
					firstTimeUrgent = 0;
				}
				
				// Do the possibly-fake-IPs detection.
				// If we have one or two peers connected now, reporting real IPs, and 
				// if there is a locally detected address they are different to it, 
				// and other peers have been connected, then maybe we need to redetect
				// to make sure we're not being spoofed.
				
				boolean maybeFake = false;
			
				if(!detector.hasDirectlyDetectedIP()) {
					
					if((conns.length > 0) && (conns.length < 3)) {
						// No locally detected IP, only one or two connections.
						// Have we had more relatively recently?
						int count = 0;
						for(int i=0;i<peers.length;i++) {
							PeerNode p = peers[i];
							if((!p.isConnected()) || (now - p.lastReceivedPacketTime() < 5*60*1000)) {
								// Not connected now but has been within the past 5 minutes.
								count++;
							}
						}
						if(count > 2) {
							if(logMINOR) Logger.minor(this, "Recently connected peers count: "+count);
							maybeFake = true;
						}
					}
				}
				
				if(maybeFake) {
					if(logMINOR) Logger.minor(this, "Possible fake IPs being fed to us, may detect...");
					if(firstTimeMaybeFakePeers <= 0)
						firstTimeMaybeFakePeers = now;
					
					if((now - firstTimeMaybeFakePeers) > 2*60*1000) {
						// MaybeFake been true for 2 minutes.
						detect = true;
					}
				
				} else {
					if(logMINOR) Logger.minor(this, "Not fake");
					firstTimeMaybeFakePeers = 0;
				}
			
				if(detect) {
					if(now - lastDetectAttemptEndedTime < 60*60*1000) {
						// Only try every hour
						if(logMINOR) Logger.minor(this, "Only trying once per hour");
						return;
					}
					
					startDetect();
				}
				
			}
		}
		
	}

	private void startDetect() {
		if(logMINOR) Logger.minor(this, "Detecting...");
		synchronized(this) {
			runner = new DetectorRunner();
			Thread t = new Thread(runner);
			t.setDaemon(true);
			t.start();
		}
	}

	public class DetectorRunner implements Runnable {

		public void run() {
			try {
				realRun();
			} catch (OutOfMemoryError e) {
				OOMHandler.handleOOM(e);
			} catch (Throwable t) {
				Logger.error(this, "Caught "+t, t);
			}
		}
		
		public void realRun() {
			if(logMINOR) Logger.minor(this, "Running STUN detection");
			try {
				FredPluginIPDetector[] run = plugins;
				Vector v = new Vector();
				for(int i=0;i<run.length;i++) {
					DetectedIP[] detected = null;
					try {
						detected = run[i].getAddress();
					} catch (Throwable t) {
						Logger.error(this, "Caught "+t, t);
					}
					if(detected != null) {
						for(int j=0;j<detected.length;j++)
							v.add(detected[j]);
					}
				}
				synchronized(IPDetectorPluginManager.this) {
					lastDetectAttemptEndedTime = System.currentTimeMillis();
					boolean failed = false;
					if(v.isEmpty()) {
						if(logMINOR) Logger.minor(this, "No IPs found");
						failed = true;
					} else {
						failed = true;
						for(int i=0;i<v.size();i++) {
							DetectedIP ip = (DetectedIP) v.get(i);
							if(!((ip.publicAddress == null) || !IPUtil.isValidAddress(ip.publicAddress, false))) {
								if(logMINOR) Logger.minor(this, "Address checked out");
								failed = false;
							}
						}
					}
					if(failed) {
						if(logMINOR) Logger.minor(this, "Failed");
						lastDetectAttemptFailed = true;
						return;
					}
				}
				
				// Node does not know about individual interfaces, so just process the lot.
				
//				// Now tell the node
//				HashMap map = new LinkedHashMap();
//				for(int i=0;i<v.size();i++) {
//					DetectedIP d = (DetectedIP) v.get(i);
//					InetAddress addr = d.publicAddress;
//					if(!map.containsKey(addr)) {
//						map.put(addr, d);
//					} else {
//						DetectedIP oldD = (DetectedIP) map.get(addr);
//						if(!oldD.equals(d)) {
//							if(d.natType != DetectedIP.NOT_SUPPORTED) {
//								if(oldD.natType < d.natType) {
//									// Higher value = more restrictive.
//									// Assume the worst.
//									map.put(addr, d);
//								}
//							}
//						}
//					}
//				}
//				DetectedIP[] list = (DetectedIP[]) map.values().toArray(new DetectedIP[map.size()]);
				DetectedIP[] list = (DetectedIP[]) v.toArray(new DetectedIP[v.size()]);
				int countOpen = 0;
				int countFullCone = 0;
				int countRestricted = 0;
				int countPortRestricted = 0;
				int countSymmetric = 0;
				int countClosed = 0;
				for(int i=0;i<list.length;i++) {
					Logger.normal(this, "Detected IP: "+list[i].publicAddress+ " : type "+list[i].natType);
					System.out.println("Detected IP: "+list[i].publicAddress+ " : type "+list[i].natType);
					switch(list[i].natType) {
					case DetectedIP.FULL_CONE_NAT:
						countFullCone++;
						break;
					case DetectedIP.FULL_INTERNET:
						countOpen++;
						break;
					case DetectedIP.NO_UDP:
						countClosed++;
						break;
					case DetectedIP.NOT_SUPPORTED:
						// Ignore
						break;
					case DetectedIP.RESTRICTED_CONE_NAT:
						countRestricted++;
						break;
					case DetectedIP.PORT_RESTRICTED_NAT:
						countPortRestricted++;
						break;
					case DetectedIP.SYMMETRIC_NAT:
					case DetectedIP.SYMMETRIC_UDP_FIREWALL:
						countSymmetric++;
						break;
					}
				}
				
				if(countClosed > 0 && (countOpen + countFullCone + countRestricted + countPortRestricted + countSymmetric) == 0) {
					proxyAlert.setAlert(noConnectionAlert);
				} else if(countSymmetric > 0 && (countOpen + countFullCone + countRestricted + countPortRestricted == 0)) {
					proxyAlert.setAlert(symmetricAlert);
				} else if(countPortRestricted > 0 && (countOpen + countFullCone + countRestricted == 0)) {
					proxyAlert.setAlert(portRestrictedAlert);
				} else if(countRestricted > 0 && (countOpen + countFullCone == 0)) {
					proxyAlert.setAlert(restrictedAlert);
				} else if(countFullCone > 0 && countOpen == 0) {
					proxyAlert.setAlert(fullConeAlert);
				} else if(countOpen > 0) {
					proxyAlert.setAlert(connectedAlert);
				}
				detector.processDetectedIPs(list);
			} finally {
				runner = null;
				detector.hasDetectedPM();
			}
		}

	}

	public boolean isEmpty() {
		return plugins.length == 0;
	}

}
