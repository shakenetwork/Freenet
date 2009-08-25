/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.clients.http;

import java.io.IOException;
import java.net.URI;

import freenet.client.HighLevelSimpleClient;
import freenet.config.BooleanOption;
import freenet.config.Config;
import freenet.config.ConfigCallback;
import freenet.config.EnumerableOptionCallback;
import freenet.config.InvalidConfigValueException;
import freenet.config.NodeNeedRestartException;
import freenet.config.Option;
import freenet.config.SubConfig;
import freenet.config.WrapperConfig;
import freenet.l10n.L10n;
import freenet.node.MasterKeysFileTooBigException;
import freenet.node.MasterKeysFileTooShortException;
import freenet.node.MasterKeysWrongPasswordException;
import freenet.node.Node;
import freenet.node.NodeClientCore;
import freenet.node.Node.AlreadySetPasswordException;
import freenet.node.useralerts.AbstractUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.api.BooleanCallback;
import freenet.support.api.HTTPRequest;

/**
 * Node Configuration Toadlet. Accessible from <code>http://.../config/</code>.
 */
// FIXME: add logging, comments
public class ConfigToadlet extends Toadlet implements LinkEnabledCallback {
	// If a setting has to be more than a meg, something is seriously wrong!
	private static final int MAX_PARAM_VALUE_SIZE = 1024*1024;
	private final SubConfig subConfig;
	private final Config config;
	private final NodeClientCore core;
	private final Node node;
	private boolean needRestart = false;
	private NeedRestartUserAlert needRestartUserAlert;

	/**
	 * Prompt for node restart
	 */
	private class NeedRestartUserAlert extends AbstractUserAlert {
		@Override
		public String getTitle() {
			return l10n("needRestartTitle");
		}

		@Override
		public String getText() {
			return getHTMLText().toString();
		}

		@Override
		public String getShortText() {
			return l10n("needRestartShort");
		}

		@Override
		public HTMLNode getHTMLText() {
			HTMLNode alertNode = new HTMLNode("div");
			alertNode.addChild("#", l10n("needRestart"));

			if (node.isUsingWrapper()) {
				alertNode.addChild("br");
				HTMLNode restartForm = alertNode.addChild("form", new String[] { "action", "method", "enctype", "id",  "accept-charset" },
						new String[] { "/", "post", "multipart/form-data", "restartForm", "utf-8"} ).addChild("div");
				restartForm.addChild("input", new String[] { "type", "name", "value" },
						new String[] { "hidden", "formPassword", node.clientCore.formPassword });
				restartForm.addChild("div");
				restartForm.addChild("input",//
						new String[] { "type", "name" },//
						new String[] { "hidden", "restart" });
				restartForm.addChild("input", //
						new String[] { "type", "name", "value" },//
						new String[] { "submit", "restart2",//
				                l10n("restartNode") });
			}

			return alertNode;
		}

		@Override
		public short getPriorityClass() {
			return UserAlert.WARNING;
		}

		@Override
		public boolean isValid() {
			return needRestart;
		}

		@Override
		public boolean userCanDismiss() {
			return false;
		}
	}

	ConfigToadlet(HighLevelSimpleClient client, Config conf, SubConfig subConfig, Node node, NodeClientCore core) {
		super(client);
		config=conf;
		this.core = core;
		this.node = node;
		this.subConfig = subConfig;
	}

	public void handleMethodPOST(URI uri, HTTPRequest request, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		if (!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, L10n.getString("Toadlet.unauthorizedTitle"), L10n
			        .getString("Toadlet.unauthorized"));
			return;
		}
		
		String pass = request.getPartAsString("formPassword", 32);
		if((pass == null) || !pass.equals(core.formPassword)) {
			MultiValueTable<String,String> headers = new MultiValueTable<String,String>();
			headers.put("Location", path());
			ctx.sendReplyHeaders(302, "Found", headers, null, 0);
			return;
		}
		
		SubConfig[] sc = config.getConfigs();
		StringBuilder errbuf = new StringBuilder();
		
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		
		for(int i=0; i<sc.length ; i++){
			Option<?>[] o = sc[i].getOptions();
			String prefix = sc[i].getPrefix();
			String configName;
			
			for(int j=0; j<o.length; j++){
				configName=o[j].getName();
				if(logMINOR) Logger.minor(this, "Setting "+prefix+ '.' +configName);
				
				// we ignore unreconized parameters
				if(request.isPartSet(prefix+ '.' +configName)) {
					String value = request.getPartAsString(prefix+ '.' +configName, MAX_PARAM_VALUE_SIZE);
					if(!(o[j].getValueString().equals(value))){
						if(logMINOR) Logger.minor(this, "Setting "+prefix+ '.' +configName+" to "+value);
						try{
							o[j].setValue(value);
						} catch (InvalidConfigValueException e) {
							errbuf.append(o[j].getName()).append(' ').append(e.getMessage()).append('\n');
						} catch (NodeNeedRestartException e) {
							needRestart = true;
						} catch (Exception e){
                            errbuf.append(o[j].getName()).append(' ').append(e).append('\n');
							Logger.error(this, "Caught "+e, e);
						}
					}
				}
			}
			
			// Wrapper params
			String wrapperConfigName = "wrapper.java.maxmemory";
			if(request.isPartSet(wrapperConfigName)) {
				String value = request.getPartAsString(wrapperConfigName, MAX_PARAM_VALUE_SIZE);
				if(!WrapperConfig.getWrapperProperty(wrapperConfigName).equals(value)) {
					if(logMINOR) Logger.minor(this, "Setting "+wrapperConfigName+" to "+value);
					WrapperConfig.setWrapperProperty(wrapperConfigName, value);
				}
			}
		}
		core.storeConfig();
		
		PageNode page = ctx.getPageMaker().getPageNode(l10n("appliedTitle"), ctx, this.node);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		if (errbuf.length() == 0) {
			HTMLNode content = ctx.getPageMaker().getInfobox("infobox-success", l10n("appliedTitle"), contentNode, "configuration-applied", true);
			content.addChild("#", l10n("appliedSuccess"));
			
			if (needRestart) {
				content.addChild("br");
				content.addChild("#", l10n("needRestart"));

				if (node.isUsingWrapper()) {
					content.addChild("br");
					HTMLNode restartForm = ctx.addFormChild(content, "/", "restartForm");
					restartForm.addChild("input",//
					        new String[] { "type", "name" },//
					        new String[] { "hidden", "restart" });
					restartForm.addChild("input", //
					        new String[] { "type", "name", "value" },//
					        new String[] { "submit", "restart2",//
					                l10n("restartNode") });
				}
				
				if (needRestartUserAlert == null) {
					needRestartUserAlert = new NeedRestartUserAlert();
					node.clientCore.alerts.register(needRestartUserAlert);
				}
			}
		} else {
			HTMLNode content = ctx.getPageMaker().getInfobox("infobox-error", l10n("appliedFailureTitle"), contentNode, "configuration-error", true).
				addChild("div", "class", "infobox-content");
			content.addChild("#", l10n("appliedFailureExceptions"));
			content.addChild("br");
			content.addChild("#", errbuf.toString());
		}
		
		HTMLNode content = ctx.getPageMaker().getInfobox("infobox-normal", l10n("possibilitiesTitle"), contentNode, "configuration-possibilities", false);
		content.addChild("a", new String[]{"href", "title"}, new String[]{path(), l10n("shortTitle")}, l10n("returnToNodeConfig"));
		content.addChild("br");
		addHomepageLink(content);

		writeHTMLReply(ctx, 200, "OK", pageNode.generate());
		
	}
	
	private static final String l10n(String string) {
		return L10n.getString("ConfigToadlet." + string);
	}

	public void handleMethodGET(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException {
		
		if(!ctx.isAllowedFullAccess()) {
			super.sendErrorPage(ctx, 403, L10n.getString("Toadlet.unauthorizedTitle"), L10n.getString("Toadlet.unauthorized"));
			return;
		}
		
		final int mode = ctx.getPageMaker().parseMode(req, container);
		
		PageNode page = ctx.getPageMaker().getPageNode(L10n.getString("ConfigToadlet.fullTitle", new String[] { "name" }, new String[] { node.getMyName() }), ctx, this.node);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;
		
		contentNode.addChild(core.alerts.createSummary());
		
		ctx.getPageMaker().drawModeSelectionArray(core, container, contentNode, mode);
		
		HTMLNode infobox = contentNode.addChild("div", "class", "infobox infobox-normal");
		infobox.addChild("div", "class", "infobox-header", l10n("title"));
		HTMLNode configNode = infobox.addChild("div", "class", "infobox-content");
		HTMLNode formNode = ctx.addFormChild(configNode, path(), "configForm");
		
		if(subConfig.getPrefix().equals("node") && WrapperConfig.canChangeProperties()) {
			String configName = "wrapper.java.maxmemory";
			String curValue = WrapperConfig.getWrapperProperty(configName);
			if(curValue != null) {
				formNode.addChild("div", "class", "configprefix", l10n("wrapper"));
				HTMLNode list = formNode.addChild("ul", "class", "config");
				HTMLNode item = list.addChild("li");
				// FIXME how to get the real default???
				String defaultValue = "128";
				item.addChild("span", new String[]{ "class", "title", "style" },
						new String[]{ "configshortdesc", L10n.getString("ConfigToadlet.defaultIs", new String[] { "default" }, new String[] { defaultValue }),
						"cursor: help;" }).addChild(L10n.getHTMLNode("WrapperConfig."+configName+".short"));
				item.addChild("span", "class", "config").addChild("input", new String[] { "type", "class", "name", "value" }, new String[] { "text", "config", configName, curValue });
				item.addChild("span", "class", "configlongdesc").addChild(L10n.getHTMLNode("WrapperConfig."+configName+".long"));
			}
		}
		
			short displayedConfigElements = 0;
			
			Option<?>[] o = subConfig.getOptions();
			HTMLNode configGroupUlNode = new HTMLNode("ul", "class", "config");
			
			for(int j=0; j<o.length; j++){
				if(! (mode == PageMaker.MODE_SIMPLE && o[j].isExpert())){
					displayedConfigElements++;
					String configName = o[j].getName();
					
					HTMLNode configItemNode = configGroupUlNode.addChild("li");
					configItemNode.addChild("span", new String[]{ "class", "title", "style" },
							new String[]{ "configshortdesc", L10n.getString("ConfigToadlet.defaultIs", new String[] { "default" }, new String[] { o[j].getDefault() }) + (mode >= PageMaker.MODE_ADVANCED ? " ["+subConfig.getPrefix() + '.' + o[j].getName() + ']' : ""),
							"cursor: help;" }).addChild(L10n.getHTMLNode(o[j].getShortDesc()));
					HTMLNode configItemValueNode = configItemNode.addChild("span", "class", "config");
					if(o[j].getValueString() == null){
						Logger.error(this, subConfig.getPrefix() + configName + "has returned null from config!);");
						continue;
					}
					
					ConfigCallback<?> callback = o[j].getCallback();
					if(callback instanceof EnumerableOptionCallback)
						configItemValueNode.addChild(addComboBox((EnumerableOptionCallback) callback, subConfig,
						        configName, callback.isReadOnly()));
					else if(callback instanceof BooleanCallback)
						configItemValueNode.addChild(addBooleanComboBox(((BooleanOption) o[j]).getValue(), subConfig,
						        configName, callback.isReadOnly()));
					else if (callback.isReadOnly())
						configItemValueNode.addChild("input", //
						        new String[] { "type", "class", "disabled", "alt", "name", "value" }, //
						        new String[] { "text", "config", "disabled", o[j].getShortDesc(),
						                subConfig.getPrefix() + '.' + configName, o[j].getValueString() });
					else
						configItemValueNode.addChild("input",//
						        new String[] { "type", "class", "alt", "name", "value" }, //
						        new String[] { "text", "config", o[j].getShortDesc(),
						                subConfig.getPrefix() + '.' + configName, o[j].getValueString() });

					configItemNode.addChild("span", "class", "configlongdesc").addChild(L10n.getHTMLNode(o[j].getLongDesc()));
				}
			}
			
			if(displayedConfigElements>0) {
				formNode.addChild("div", "class", "configprefix", l10n(subConfig.getPrefix()));
				formNode.addChild("a", "id", subConfig.getPrefix());
				formNode.addChild(configGroupUlNode);
			}
		
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "submit", l10n("apply")});
		formNode.addChild("input", new String[] { "type", "value" }, new String[] { "reset",  l10n("reset")});
		
		this.writeHTMLReply(ctx, 200, "OK", pageNode.generate());
	}

	private HTMLNode addComboBox(EnumerableOptionCallback o, SubConfig sc, String name, boolean disabled) {
		HTMLNode result;
		if (disabled)
			result = new HTMLNode("select", //
			        new String[] { "name", "disabled" }, //
			        new String[] { sc.getPrefix() + '.' + name, "disabled" });
		else
			result = new HTMLNode("select", "name", sc.getPrefix() + '.' + name);
		
		String[] possibleValues = o.getPossibleValues();
		for(int i=0; i<possibleValues.length; i++) {
			if(possibleValues[i].equals(o.get()))
				result.addChild("option", new String[] { "value", "selected" }, new String[] { possibleValues[i], "selected" }, possibleValues[i]);
			else
				result.addChild("option", "value", possibleValues[i], possibleValues[i]);
		}
		
		return result;
	}
	
	private HTMLNode addBooleanComboBox(boolean value, SubConfig sc, String name, boolean disabled) {
		HTMLNode result;
		if (disabled)
			result = new HTMLNode("select", //
			        new String[] { "name", "disabled" }, //
			        new String[] { sc.getPrefix() + '.' + name, "disabled" });
		else
			result = new HTMLNode("select", "name", sc.getPrefix() + '.' + name);

		if (value) {
			result.addChild("option", new String[] { "value", "selected" }, new String[] { "true", "selected" },
			        l10n("true"));
			result.addChild("option", "value", "false", l10n("false"));
		} else {
			result.addChild("option", "value", "true", l10n("true"));
			result.addChild("option", new String[] { "value", "selected" }, new String[] { "false", "selected" },
			        l10n("false"));
		}
		
		return result;
	}

	@Override
	public String path() {
		return "/config/"+subConfig.getPrefix();
	}

	public boolean isEnabled(ToadletContext ctx) {
		Option<?>[] o = subConfig.getOptions();
		if(core.isAdvancedModeEnabled()) return true;
		for(Option<?> option : o)
			if(!option.isExpert()) return true;
		return false;
	}
}
