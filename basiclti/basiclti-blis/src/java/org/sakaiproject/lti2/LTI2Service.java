/**
 * $URL$
 * $Id$
 *
 * Copyright (c) 2009 The Sakai Foundation
 *
 * Licensed under the Educational Community License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *			 http://www.opensource.org/licenses/ECL-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sakaiproject.lti2;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.Properties;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.oauth.OAuthAccessor;
import net.oauth.OAuthConsumer;
import net.oauth.OAuthMessage;
import net.oauth.OAuthValidator;
import net.oauth.SimpleOAuthValidator;
import net.oauth.server.OAuthServlet;
import net.oauth.signature.OAuthSignatureMethod;

import org.json.simple.JSONValue;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;

import org.sakaiproject.component.cover.ComponentManager;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.imsglobal.basiclti.BasicLTIUtil;
import org.sakaiproject.component.cover.ServerConfigurationService;
import org.sakaiproject.util.ResourceLoader;
import org.sakaiproject.basiclti.util.SakaiBLTIUtil;
import org.imsglobal.basiclti.BasicLTIConstants;
import org.imsglobal.lti2.LTI2Constants;
import org.imsglobal.lti2.objects.*;
import org.sakaiproject.lti2.SakaiLTI2Services;

import org.imsglobal.json.IMSJSONRequest;

import org.sakaiproject.lti.api.LTIService;
import org.sakaiproject.util.foorm.SakaiFoorm;
import org.sakaiproject.util.foorm.FoormUtil;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.ObjectWriter;

/**
 * Notes:
 * 
 * This program is directly exposed as a URL to receive IMS Basic LTI messages
 * so it must be carefully reviewed and any changes must be looked at carefully.
 * 
 */

@SuppressWarnings("deprecation")
public class LTI2Service extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private static Log M_log = LogFactory.getLog(LTI2Service.class);
	private static ResourceLoader rb = new ResourceLoader("blis");

	protected static SakaiFoorm foorm = new SakaiFoorm();

	protected static LTIService ltiService = null;

	protected String resourceUrl = null;
	protected Service_offered LTI2ResultItem = null;
	protected Service_offered LTI2LtiLinkSettings = null;
	protected Service_offered LTI2ToolProxyBindingSettings = null;
	protected Service_offered LTI2ToolProxySettings = null;

	// Copy these in...
	private static final String SVC_tc_profile = SakaiBLTIUtil.SVC_tc_profile;
	private static final String SVC_tc_registration = SakaiBLTIUtil.SVC_tc_registration;
	private static final String SVC_Settings = SakaiBLTIUtil.SVC_Settings;
	private static final String SVC_Result = SakaiBLTIUtil.SVC_Result;

	private static final String SET_LtiLink = SakaiBLTIUtil.SET_LtiLink;
	private static final String SET_ToolProxyBinding = SakaiBLTIUtil.SET_ToolProxyBinding;
	private static final String SET_ToolProxy = SakaiBLTIUtil.SET_ToolProxy;

	private static final String LTI1_PATH = SakaiBLTIUtil.LTI1_PATH;
	private static final String LTI2_PATH = SakaiBLTIUtil.LTI2_PATH;

	private static final String EMPTY_JSON_OBJECT = "{\n}\n";

	private static final String APPLICATION_JSON = "application/json";

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		if ( ltiService == null ) ltiService = (LTIService) ComponentManager.get("org.sakaiproject.lti.api.LTIService");

		resourceUrl = SakaiBLTIUtil.getOurServerUrl() + LTI2_PATH;
		LTI2ResultItem = StandardServices.LTI2ResultItem(resourceUrl 
			+ SVC_Result + "/{" + BasicLTIConstants.LIS_RESULT_SOURCEDID + "}");
		LTI2LtiLinkSettings = StandardServices.LTI2LtiLinkSettings(resourceUrl 
			+ SVC_Settings + "/" + SET_LtiLink + "/{" + BasicLTIConstants.RESOURCE_LINK_ID + "}");
		LTI2ToolProxyBindingSettings = StandardServices.LTI2ToolProxySettings(resourceUrl 
			+ SVC_Settings + "/" + SET_ToolProxyBinding + "/{" + BasicLTIConstants.RESOURCE_LINK_ID + "}");
		LTI2ToolProxySettings = StandardServices.LTI2ToolProxySettings(resourceUrl 
			+ SVC_Settings + "/" + SET_ToolProxy + "/{" + LTI2Constants.TOOL_PROXY_GUID + "}");
	}

	protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doPost(request,response);
	}

	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		doPost(request,response);
	}

	protected void doPost(HttpServletRequest request, HttpServletResponse response) 
		throws ServletException, IOException 
	{
		try { 
			doRequest(request, response);
		} catch (Exception e) {
			String ipAddress = request.getRemoteAddr();
			String uri = request.getRequestURI();
			M_log.warn("General LTI2 Failure URI="+uri+" IP=" + ipAddress);
			e.printStackTrace();
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); 
			doErrorJSON(request, response, null, "General failure", e);
		}
	}

	@SuppressWarnings("unchecked")
	protected void doRequest(HttpServletRequest request, HttpServletResponse response) 
		throws ServletException, IOException 
	{
		String ipAddress = request.getRemoteAddr();
		M_log.debug("Basic LTI Service request from IP=" + ipAddress);

		String rpi = request.getPathInfo();
		String uri = request.getRequestURI();
		String [] parts = uri.split("/");
		if ( parts.length < 4 ) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST); 
			doErrorJSON(request, response, null, "Incorrect url format", null);
			return;
		}
		String controller = parts[3];
		if ( SVC_tc_profile.equals(controller) && parts.length == 5 ) {
			String profile_id = parts[4];
			getToolConsumerProfile(request,response,profile_id);
			return;
		} else if ( SVC_tc_registration.equals(controller) && parts.length == 5 ) {
			String profile_id = parts[4];
			registerToolProviderProfile(request, response, profile_id);
			return;
		} else if ( SVC_Result.equals(controller) && parts.length == 5 ) {
			String sourcedid = parts[4];
			handleResultRequest(request, response, sourcedid);
			return;
		} else if ( SVC_Settings.equals(controller) && parts.length >= 6 ) {
			handleSettingsRequest(request, response, parts);
			return;
		}

		IMSJSONRequest jsonRequest = new IMSJSONRequest(request);
		if ( jsonRequest.valid ) {
			System.out.println(jsonRequest.getPostBody());
		}

		response.setStatus(HttpServletResponse.SC_NOT_IMPLEMENTED); 
		M_log.warn("Unknown request="+uri);
		doErrorJSON(request, response, null, "Unknown request="+uri, null);
	}

	protected void getToolConsumerProfile(HttpServletRequest request, 
			HttpServletResponse response,String profile_id)
	{
		Map<String,Object> deploy = ltiService.getDeployForConsumerKeyDao(profile_id);
		if ( deploy == null ) {
			response.setStatus(HttpServletResponse.SC_NOT_FOUND); 
			return;
		}

		ToolConsumer consumer = getToolConsumerProfile(deploy, profile_id);

		ObjectMapper mapper = new ObjectMapper();
		try {
			// http://stackoverflow.com/questions/6176881/how-do-i-make-jackson-pretty-print-the-json-content-it-generates
			ObjectWriter writer = mapper.defaultPrettyPrintingWriter();
			// ***IMPORTANT!!!*** for Jackson 2.x use the line below instead of the one above: 
			// ObjectWriter writer = mapper.writer().withDefaultPrettyPrinter();
			// System.out.println(mapper.writeValueAsString(consumer));
			response.setContentType(APPLICATION_JSON);
			PrintWriter out = response.getWriter();
			out.println(writer.writeValueAsString(consumer));
			// System.out.println(writer.writeValueAsString(consumer));
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}


	protected ToolConsumer getToolConsumerProfile(Map<String, Object> deploy, String profile_id)
	{
		String serverUrl = SakaiBLTIUtil.getOurServerUrl();
		Product_family fam = new Product_family("SakaiCLE", "CLE", "Sakai Project",
				"Amazing open source Collaboration and Learning Environment.", 
				"http://www.sakaiproject.org", "support@sakaiproject.org");

		Product_info info = new Product_info("CTools", "4.0", "The Sakai installation for UMich", fam);
		Service_owner sowner = new Service_owner("https://ctools.umich.edu/", "CTools", "Description", "support@ctools.umich.edu");
		Service_provider powner = new Service_provider("https://ctools.umich.edu/", "CTools", "Description", "support@ctools.umich.edu");

		Product_instance instance = new Product_instance("ctools-001", info, sowner, powner, "support@ctools.umich.edu");

		ToolConsumer consumer = new ToolConsumer(profile_id+"", resourceUrl, instance);
		List<String> capabilities = consumer.getCapability_offered();

		if (foorm.getLong(deploy.get(LTIService.LTI_SENDEMAILADDR)) > 0 ) {
			capabilities.add("Person.email.primary");
		}

		if (foorm.getLong(deploy.get(LTIService.LTI_SENDNAME)) > 0 ) {
			capabilities.add("User.username");
			capabilities.add("Person.name.fullname");
			capabilities.add("Person.name.given");
			capabilities.add("Person.name.family");
			capabilities.add("Person.name.full");
		}

		List<Service_offered> services = consumer.getService_offered();
		services.add(StandardServices.LTI2Registration(serverUrl + LTI2_PATH + SVC_tc_registration + "/" + profile_id));

		String allowOutcomes = ServerConfigurationService.getString(SakaiBLTIUtil.BASICLTI_OUTCOMES_ENABLED, SakaiBLTIUtil.BASICLTI_OUTCOMES_ENABLED_DEFAULT);
		if ("true".equals(allowOutcomes) && foorm.getLong(deploy.get(LTIService.LTI_ALLOWOUTCOMES)) > 0 ) {
			services.add(LTI2ResultItem);
			services.add(StandardServices.LTI1Outcomes(serverUrl+LTI1_PATH));
			services.add(SakaiLTI2Services.BasicOutcomes(serverUrl+LTI1_PATH));
			capabilities.add("Result.sourcedId");
			capabilities.add("Result.autocreate");
			capabilities.add("Result.url");
		}

		String allowRoster = ServerConfigurationService.getString(SakaiBLTIUtil.BASICLTI_ROSTER_ENABLED, SakaiBLTIUtil.BASICLTI_ROSTER_ENABLED_DEFAULT);
		if ("true".equals(allowRoster) && foorm.getLong(deploy.get(LTIService.LTI_ALLOWROSTER)) > 0 ) {
			services.add(SakaiLTI2Services.BasicRoster(serverUrl+LTI1_PATH));
		}

		String allowSettings = ServerConfigurationService.getString(SakaiBLTIUtil.BASICLTI_SETTINGS_ENABLED, SakaiBLTIUtil.BASICLTI_SETTINGS_ENABLED_DEFAULT);
		if ("true".equals(allowSettings) && foorm.getLong(deploy.get(LTIService.LTI_ALLOWSETTINGS)) > 0 ) {
			services.add(SakaiLTI2Services.BasicSettings(serverUrl+LTI1_PATH));
			services.add(LTI2LtiLinkSettings);
			services.add(LTI2ToolProxySettings);
			services.add(LTI2ToolProxyBindingSettings);
			capabilities.add("LtiLink.custom.url");
			capabilities.add("ToolProxy.custom.url");
			capabilities.add("ToolProxyBinding.custom.url");
		}

		String allowLori = ServerConfigurationService.getString(SakaiBLTIUtil.BASICLTI_LORI_ENABLED, SakaiBLTIUtil.BASICLTI_LORI_ENABLED_DEFAULT);
		if ("true".equals(allowLori) && foorm.getLong(deploy.get(LTIService.LTI_ALLOWLORI)) > 0 ) {
			services.add(SakaiLTI2Services.LORI_XML(serverUrl+LTI1_PATH));
		}
		return consumer;
	}

	public void registerToolProviderProfile(HttpServletRequest request,HttpServletResponse response, 
			String profile_id) throws java.io.IOException
	{
		Map<String,Object> deploy = ltiService.getDeployForConsumerKeyDao(profile_id);
		if ( deploy == null ) {
			response.setStatus(HttpServletResponse.SC_NOT_FOUND); 
			return;
		}
		Long deployKey = foorm.getLong(deploy.get(LTIService.LTI_ID));

		// See if we can even register...
		Long reg_state = foorm.getLong(deploy.get(LTIService.LTI_REG_STATE));
		String key = null;
		String secret = null;
		if ( reg_state == 0 ) {
			key = (String) deploy.get(LTIService.LTI_REG_KEY);
			secret = (String) deploy.get(LTIService.LTI_REG_PASSWORD);
		} else {
			key = (String) deploy.get(LTIService.LTI_CONSUMERKEY);
			secret = (String) deploy.get(LTIService.LTI_SECRET);
		}

		IMSJSONRequest jsonRequest = new IMSJSONRequest(request);

		if ( ! jsonRequest.valid ) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			doErrorJSON(request, response, jsonRequest, "Request is not in a valid format", null);
			return;
		}
		// System.out.println(jsonRequest.getPostBody());

		// Lets check the signature
		if ( key == null || secret == null ) {
			response.setStatus(HttpServletResponse.SC_FORBIDDEN); 
			doErrorJSON(request, response, jsonRequest, "Deployment is missing credentials", null);
			return;
		}

		jsonRequest.validateRequest(key, secret, request);
		if ( !jsonRequest.valid ) {
			response.setStatus(HttpServletResponse.SC_FORBIDDEN); 
			doErrorJSON(request, response, jsonRequest, "OAuth signature failure", null);
			return;
		}

		JSONObject providerProfile = (JSONObject) JSONValue.parse(jsonRequest.getPostBody());
		// System.out.println("OBJ:"+providerProfile);
		if ( providerProfile == null  ) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			doErrorJSON(request, response, jsonRequest, "JSON parse failed", null);
			return;
		}

		JSONObject default_custom = (JSONObject) providerProfile.get(LTI2Constants.CUSTOM);

		JSONObject security_contract = (JSONObject) providerProfile.get(LTI2Constants.SECURITY_CONTRACT);
		if ( security_contract == null  ) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			doErrorJSON(request, response, jsonRequest, "JSON missing security_contract", null);
			return;
		}

		String shared_secret = (String) security_contract.get(LTI2Constants.SHARED_SECRET);
		if ( shared_secret == null  ) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			doErrorJSON(request, response, jsonRequest, "JSON missing shared_secret", null);
			return;
		}

		// Blank out the new shared secret
		security_contract.put(LTI2Constants.SHARED_SECRET, "*********");

		// Make sure that the requested services are a subset of the offered services
		ToolConsumer consumer = getToolConsumerProfile(deploy, profile_id);

		JSONArray tool_services = (JSONArray) security_contract.get(LTI2Constants.TOOL_SERVICE);
		List<Service_offered> services_offered = consumer.getService_offered();

		if ( tool_services != null ) for (Object o : tool_services) {
			JSONObject tool_service = (JSONObject) o;
			String json_service = (String) tool_service.get(LTI2Constants.SERVICE);

			boolean found = false;
			for (Service_offered service : services_offered ) {
				String service_endpoint = service.getEndpoint();
				if ( service_endpoint.equals(json_service) ) {
					found = true;
					break;
				}
			}
			if ( ! found ) {
				response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
				doErrorJSON(request, response, jsonRequest, "Service not allowed: "+json_service, null);
				return;
			}
		}

		// Parse the tool profile bit and extract the tools with error checking
		List<Properties> theTools = new ArrayList<Properties> ();
		Properties info = new Properties();
		try {
			String retval = BasicLTIUtil.parseToolProfile(theTools, info, providerProfile);
			if ( retval != null ) {
				response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
				doErrorJSON(request, response, jsonRequest, retval, null);
				return;
			}
		}
		catch (Exception e ) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			doErrorJSON(request, response, jsonRequest, "Exception:"+ e.getLocalizedMessage(), e);
			return;
		}

		if ( theTools.size() < 1 ) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			doErrorJSON(request, response, jsonRequest, "No tools found in profile", null);
			return;
		}

		// Check all the capabilities requested by all the tools comparing against consumer
		List<String> capabilities = consumer.getCapability_offered();
		for ( Properties theTool : theTools ) {
			String ec = (String) theTool.get("enabled_capability");
			JSONArray enabled_capability = (JSONArray) JSONValue.parse(ec);
			if ( enabled_capability != null ) for (Object o : enabled_capability) {
				ec = (String) o;
				if ( capabilities.contains(ec) ) continue;
				response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
				doErrorJSON(request, response, jsonRequest, "Capability not permitted="+ec, null);
				return;
			}
		}

		Map<String, Object> deployUpdate = new TreeMap<String, Object> ();

		shared_secret = SakaiBLTIUtil.encryptSecret(shared_secret);
		deployUpdate.put(LTIService.LTI_SECRET, shared_secret);

		// Indicate ready to validate and kill the interim info
		deployUpdate.put(LTIService.LTI_REG_STATE, LTIService.LTI_REG_STATE_REGISTERED);
		deployUpdate.put(LTIService.LTI_REG_KEY, "");
		deployUpdate.put(LTIService.LTI_REG_PASSWORD, "");
		if ( default_custom != null ) deployUpdate.put(LTIService.LTI_SETTINGS, default_custom.toString());
		deployUpdate.put(LTIService.LTI_REG_PROFILE, providerProfile.toString());

		M_log.debug("deployUpdate="+deployUpdate);

		Object obj = ltiService.updateDeployDao(deployKey, deployUpdate);
		boolean success = ( obj instanceof Boolean ) && ( (Boolean) obj == Boolean.TRUE);
		if ( ! success ) {
			M_log.warn("updateDeployDao fail deployKey="+deployKey+"\nretval="+obj+"\ndata="+deployUpdate);
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			doErrorJSON(request, response, jsonRequest, "Failed update of deployment="+deployKey, null);
			return;
		}

		// Share our happiness with the Tool Provider
		Map jsonResponse = new TreeMap();
		jsonResponse.put(LTI2Constants.CONTEXT,StandardServices.TOOLPROXY_ID_CONTEXT);
		jsonResponse.put(LTI2Constants.TYPE, StandardServices.TOOLPROXY_ID_TYPE);
		String serverUrl = ServerConfigurationService.getServerUrl();
		jsonResponse.put(LTI2Constants.JSONLD_ID, resourceUrl + SVC_tc_registration + "/" +profile_id);
		jsonResponse.put(LTI2Constants.TOOL_PROXY_GUID, profile_id);
		jsonResponse.put(LTI2Constants.CUSTOM_URL, resourceUrl + SVC_Settings + "/" + SET_ToolProxy + "/" +profile_id);
		response.setContentType(StandardServices.TOOLPROXY_ID_FORMAT);
		response.setStatus(HttpServletResponse.SC_CREATED);
		String jsonText = JSONValue.toJSONString(jsonResponse);
		M_log.debug(jsonText);
		PrintWriter out = response.getWriter();
		out.println(jsonText);
	}

	public void handleResultRequest(HttpServletRequest request,HttpServletResponse response, 
			String sourcedid) throws java.io.IOException
	{
		String allowOutcomes = ServerConfigurationService.getString(SakaiBLTIUtil.BASICLTI_OUTCOMES_ENABLED, SakaiBLTIUtil.BASICLTI_OUTCOMES_ENABLED_DEFAULT);
		if ( ! "true".equals(allowOutcomes) ) {
			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			doErrorJSON(request,response, null, "Result resources not available", null);
			return;
		}

		Object retval = null;
		IMSJSONRequest jsonRequest = null;
		if ( "GET".equals(request.getMethod()) ) { 
			retval = SakaiBLTIUtil.getGrade(sourcedid, request, ltiService);
			if ( ! (retval instanceof Map) ) {
				response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
				doErrorJSON(request,response, jsonRequest, (String) retval, null);
				return;
			}
			Map grade = (Map) retval;
			Map jsonResponse = new TreeMap();
			Map resultScore = new TreeMap();
	
			jsonResponse.put(LTI2Constants.CONTEXT,StandardServices.RESULT_CONTEXT);
			jsonResponse.put(LTI2Constants.TYPE, StandardServices.RESULT_TYPE);
			jsonResponse.put(LTI2Constants.COMMENT, grade.get(LTI2Constants.COMMENT));
			resultScore.put(LTI2Constants.TYPE, LTI2Constants.GRADE_TYPE_DECIMAL);
			resultScore.put(LTI2Constants.VALUE, grade.get(LTI2Constants.GRADE));
			jsonResponse.put(LTI2Constants.RESULTSCORE,resultScore);
			response.setContentType(StandardServices.RESULT_FORMAT);
			response.setStatus(HttpServletResponse.SC_OK);
			String jsonText = JSONValue.toJSONString(jsonResponse);
			M_log.debug(jsonText);
			PrintWriter out = response.getWriter();
			out.println(jsonText);
		} else if ( "PUT".equals(request.getMethod()) ) { 
			retval = "Error parsing input data";
			try {
				jsonRequest = new IMSJSONRequest(request);
				// System.out.println(jsonRequest.getPostBody());
				JSONObject requestData = (JSONObject) JSONValue.parse(jsonRequest.getPostBody());
				String comment = (String) requestData.get(LTI2Constants.COMMENT);
				JSONObject resultScore = (JSONObject) requestData.get(LTI2Constants.RESULTSCORE);
				String sGrade = (String) resultScore.get(LTI2Constants.VALUE);
				Double dGrade = new Double(sGrade);
				retval = SakaiBLTIUtil.setGrade(sourcedid, request, ltiService, dGrade, comment);
			} catch (Exception e) {
				retval = "Error: "+ e.getMessage();
			}
			if ( retval instanceof Boolean && (Boolean) retval ) {
				response.setStatus(HttpServletResponse.SC_OK);
			} else {
				response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			}
		} else {
			retval = "Unsupported operation:" + request.getMethod();
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
		}
	
		if ( retval instanceof String ) {
			doErrorJSON(request,response, jsonRequest, (String) retval, null);
			return;
		}
	}

	public void handleSettingsRequest(HttpServletRequest request,HttpServletResponse response, 
			String[] parts) throws java.io.IOException
	{
		String allowSettings = ServerConfigurationService.getString(SakaiBLTIUtil.BASICLTI_SETTINGS_ENABLED, SakaiBLTIUtil.BASICLTI_SETTINGS_ENABLED_DEFAULT);
		if ( ! "true".equals(allowSettings) ) {
			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
			doErrorJSON(request,response, null, "Tool settings not available", null);
			return;
		}

		String URL = SakaiBLTIUtil.getOurServletPath(request);
		String scope = parts[4];

		// Check to see if we are doing the bubble
		String bubbleStr = request.getParameter("bubble");
		String acceptHdr = request.getHeader("Accept");
		String contentHdr = request.getContentType();
System.out.println("accept="+acceptHdr+" bubble="+bubbleStr);

		if ( bubbleStr != null && bubbleStr.equals("all") &&
			acceptHdr.indexOf(StandardServices.TOOLSETTINGS_FORMAT) < 0 ) {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			doErrorJSON(request, response, null, "Simple format does not allow bubble=all", null);
			return;
		}

		boolean bubble = bubbleStr != null && "GET".equals(request.getMethod());
		boolean distinct = bubbleStr != null && "distinct".equals(bubbleStr) && "GET".equals(request.getMethod());
		boolean bubbleAll = bubbleStr != null && "all".equals(bubbleStr) && "GET".equals(request.getMethod());

		// Check our input and output formats
		boolean acceptSimple = acceptHdr == null || acceptHdr.indexOf(StandardServices.TOOLSETTINGS_SIMPLE_FORMAT) >= 0 ;
		boolean acceptComplex = acceptHdr == null || acceptHdr.indexOf(StandardServices.TOOLSETTINGS_FORMAT) >= 0 ;
		boolean inputSimple = contentHdr == null || contentHdr.indexOf(StandardServices.TOOLSETTINGS_SIMPLE_FORMAT) >= 0 ;
		boolean inputComplex = contentHdr != null && contentHdr.indexOf(StandardServices.TOOLSETTINGS_FORMAT) >= 0 ;
System.out.println("as="+acceptSimple+" ac="+acceptComplex+" is="+inputSimple+" ic="+inputComplex);

		// Check the JSON on PUT and check the oauth_body_hash
		IMSJSONRequest jsonRequest = null;
		JSONObject requestData = null;
		if ( "PUT".equals(request.getMethod()) ) {
			try {
				jsonRequest = new IMSJSONRequest(request);
				requestData = (JSONObject) JSONValue.parse(jsonRequest.getPostBody());
			} catch (Exception e) {
				response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
				doErrorJSON(request,response, jsonRequest, "Could not parse JSON", e);
				return;
			}
		}

		String consumer_key = null;
		String siteId = null;
		String placement_id = null;

		Map<String,Object> content = null;
		Long contentKey = null;
		Map<String,Object> tool = null;
		Long toolKey = null;
		Map<String,Object> proxyBinding = null;
		Long proxyBindingKey = null;
		Map<String,Object> deploy = null;
		Long deployKey = null;

		if ( SET_LtiLink.equals(scope) || SET_ToolProxyBinding.equals(scope) ) {
			placement_id = parts[5];
System.out.println("placement_id="+placement_id);
			String contentStr = placement_id.substring(8);
			contentKey = SakaiBLTIUtil.getLongKey(contentStr);
			if ( contentKey  >= 0 ) {
				// Leave off the siteId - bypass all checking - because we need to 
				// find the siteId from the content item
				content = ltiService.getContentDao(contentKey);
				if ( content != null ) siteId = (String) content.get(LTIService.LTI_SITE_ID);
			}
	
			if ( content == null || siteId == null ) {
				response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
				doErrorJSON(request,response, jsonRequest, "Bad content item", null);
				return;
			}
	
			toolKey = SakaiBLTIUtil.getLongKey(content.get(LTIService.LTI_TOOL_ID));
			if ( toolKey >= 0 ) {
				tool = ltiService.getToolDao(toolKey, siteId);
			}
		
			if ( tool == null ) {
				response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
				doErrorJSON(request,response, jsonRequest, "Bad tool item", null);
				return;
			}
	
			// Adjust the content items based on the tool items
			ltiService.filterContent(content, tool);

			// Check settings to see if we are allowed to do this 
			if (foorm.getLong(content.get(LTIService.LTI_ALLOWOUTCOMES)) > 0 ||
				foorm.getLong(tool.get(LTIService.LTI_ALLOWOUTCOMES)) > 0 ) {
				// Good news 
			} else {
				response.setStatus(HttpServletResponse.SC_FORBIDDEN);
				doErrorJSON(request,response, jsonRequest, "Item does not allow tool settings", null);
				return;
			}

		}


		if ( SET_ToolProxyBinding.equals(scope) || SET_LtiLink.equals(scope) ) {
			proxyBinding = ltiService.getProxyBindingDao(toolKey,siteId);
			if ( proxyBinding != null ) {
				proxyBindingKey = SakaiBLTIUtil.getLongKey(proxyBinding.get(LTIService.LTI_ID));
			}
		}

		// Retrieve the deployment if needed
		if ( SET_ToolProxy.equals(scope) ) {
			consumer_key = parts[5];
			deploy = ltiService.getDeployForConsumerKeyDao(consumer_key);
			if ( deploy == null ) {
				response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
				doErrorJSON(request,response, jsonRequest, "Bad deploy item", null);
				return;
			}
			deployKey = SakaiBLTIUtil.getLongKey(deploy.get(LTIService.LTI_ID));
		} else if ( bubble ) {
			deployKey = SakaiBLTIUtil.getLongKey(tool.get(LTIService.LTI_DEPLOYMENT_ID));
			if ( deployKey >= 0 ) {
				deploy = ltiService.getDeployDao(deployKey);
			}
			if ( deploy == null ) {
				response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
				doErrorJSON(request,response, jsonRequest, "Bad deploy item", null);
				return;
			}
			consumer_key = (String) deploy.get(LTIService.LTI_CONSUMERKEY);
		}

		// Check settings to see if we are allowed to do this 
		if ( deploy != null ) {
			if (foorm.getLong(deploy.get(LTIService.LTI_ALLOWOUTCOMES)) > 0 ) {
				// Good news 
			} else {
				response.setStatus(HttpServletResponse.SC_FORBIDDEN);
				doErrorJSON(request,response, jsonRequest, "Deployment does not allow tool settings", null);
				return;
			}
		}

		// Load and parse the old settings...
		JSONObject link_settings = new JSONObject ();
		JSONObject binding_settings = new JSONObject ();
		JSONObject proxy_settings = new JSONObject();
		if ( content != null ) {
			link_settings = parseSettings((String) content.get(LTIService.LTI_SETTINGS));
		}
		if ( proxyBinding != null ) {
			binding_settings = parseSettings((String) proxyBinding.get(LTIService.LTI_SETTINGS));
		}
		if ( deploy != null ) {
			proxy_settings = parseSettings((String) deploy.get(LTIService.LTI_SETTINGS));
		}

		if ( distinct && link_settings != null && scope.equals(SET_LtiLink) ) {
			Iterator i = link_settings.keySet().iterator();
			while ( i.hasNext() ) {
				String key = (String) i.next();
				if ( binding_settings != null ) binding_settings.remove(key);
				if ( proxy_settings != null ) proxy_settings.remove(key);
			}
		}

		if ( distinct && binding_settings != null && scope.equals(SET_ToolProxyBinding) ) {
			Iterator i = binding_settings.keySet().iterator();
			while ( i.hasNext() ) {
				String key = (String) i.next();
				if ( proxy_settings != null ) proxy_settings.remove(key);
			}
		}

		// Get the secret for the request...
		String oauth_secret = null;
		String endpoint = null;
		String settingsUrl = SakaiBLTIUtil.getOurServerUrl() + LTI2_PATH + SVC_Settings;
		if ( SET_LtiLink.equals(scope) ) {
			oauth_secret = (String) content.get(LTIService.LTI_SECRET);
			if ( oauth_secret == null || oauth_secret.length() < 1 ) {
				oauth_secret = (String) tool.get(LTIService.LTI_SECRET);
			}
			endpoint = settingsUrl + "/" + SET_LtiLink + "/" + placement_id;
		} else if ( SET_ToolProxyBinding.equals(scope) ) {
			oauth_secret = (String) tool.get(LTIService.LTI_SECRET);
			endpoint = settingsUrl + "/" + SET_ToolProxyBinding + "/" + placement_id;
		} else if ( SET_ToolProxy.equals(scope) ) {
			oauth_secret = (String) deploy.get(LTIService.LTI_SECRET);
			endpoint = settingsUrl + "/" + SET_ToolProxy + "/" + consumer_key;
		} else {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			doErrorJSON(request,response, jsonRequest, "Bad Setttings Scope="+scope, null);
			return;
		}

		// Validate the incoming message
		Object retval = SakaiBLTIUtil.validateMessage(request, URL, oauth_secret, consumer_key);
		if ( retval instanceof String ) {
			response.setStatus(HttpServletResponse.SC_FORBIDDEN); 
			doErrorJSON(request,response, jsonRequest, (String) retval, null);
			return;
		}

		if ( "GET".equals(request.getMethod()) && (distinct || bubbleAll) && acceptComplex ) { 
			JSONObject jsonResponse = new JSONObject();	
			jsonResponse.put(LTI2Constants.CONTEXT,StandardServices.TOOLSETTINGS_CONTEXT);
			JSONArray graph = new JSONArray();
			boolean started = false;
			if ( link_settings != null && SET_LtiLink.equals(scope) ) {
				endpoint = settingsUrl + "/" + SET_LtiLink + "/" + placement_id;
				JSONObject cjson = new JSONObject();
				cjson.put(LTI2Constants.JSONLD_ID,endpoint);
				cjson.put(LTI2Constants.TYPE,SET_LtiLink);
				cjson.put(LTI2Constants.CUSTOM,link_settings);
				graph.add(cjson);
				started = true;
			} 
			if ( binding_settings != null && ( started || SET_ToolProxyBinding.equals(scope) ) ) {
				endpoint = settingsUrl + "/" + SET_ToolProxyBinding + "/" + placement_id;
				JSONObject cjson = new JSONObject();
				cjson.put(LTI2Constants.JSONLD_ID,endpoint);
				cjson.put(LTI2Constants.TYPE,SET_ToolProxyBinding);
				cjson.put(LTI2Constants.CUSTOM,binding_settings);
				graph.add(cjson);
				started = true;
			} 
			if ( proxy_settings != null && ( started || SET_ToolProxy.equals(scope) ) ) {
				endpoint = settingsUrl + "/" + SET_ToolProxy + "/" + consumer_key;
				JSONObject cjson = new JSONObject();
				cjson.put(LTI2Constants.JSONLD_ID,endpoint);
				cjson.put(LTI2Constants.TYPE,SET_ToolProxy);
				cjson.put(LTI2Constants.CUSTOM,proxy_settings);
				graph.add(cjson);
			}
			jsonResponse.put(LTI2Constants.GRAPH,graph);
			response.setContentType(StandardServices.TOOLSETTINGS_FORMAT);
			response.setStatus(HttpServletResponse.SC_OK); 
			PrintWriter out = response.getWriter();
System.out.println("jsonResponse="+jsonResponse);
			out.println(jsonResponse.toString());
			return;
		} else if ( "GET".equals(request.getMethod()) && distinct ) {  // acceptSimple
			JSONObject jsonResponse = proxy_settings;
			if ( SET_LtiLink.equals(scope) ) {
				jsonResponse.putAll(binding_settings);
				jsonResponse.putAll(link_settings);
			} else if ( SET_ToolProxyBinding.equals(scope) ) {
				jsonResponse.putAll(binding_settings);
			}
			response.setContentType(StandardServices.TOOLSETTINGS_SIMPLE_FORMAT);
			response.setStatus(HttpServletResponse.SC_OK); 
			PrintWriter out = response.getWriter();
System.out.println("jsonResponse="+jsonResponse);
			out.println(jsonResponse.toString());
			return;
		} else if ( "GET".equals(request.getMethod()) ) { // bubble == none
System.out.println("bubble=none");
			JSONObject jsonResponse = new JSONObject();	
			jsonResponse.put(LTI2Constants.CONTEXT,StandardServices.TOOLSETTINGS_CONTEXT);
			JSONObject theSettings = null;
			if ( SET_LtiLink.equals(scope) ) {
				endpoint = settingsUrl + "/" + SET_LtiLink + "/" + placement_id;
				theSettings = link_settings;
			} else if ( SET_ToolProxyBinding.equals(scope) ) {
				endpoint = settingsUrl + "/" + SET_ToolProxyBinding + "/" + placement_id;
				theSettings = binding_settings;
			} 
			if ( SET_ToolProxy.equals(scope) ) {
				endpoint = settingsUrl + "/" + SET_ToolProxy + "/" + consumer_key;
				theSettings = proxy_settings;
			}
			if ( acceptComplex ) {
				JSONArray graph = new JSONArray();
				JSONObject cjson = new JSONObject();
				cjson.put(LTI2Constants.JSONLD_ID,endpoint);
				cjson.put(LTI2Constants.TYPE,scope);
				cjson.put(LTI2Constants.CUSTOM,theSettings);
				graph.add(cjson);
				jsonResponse.put(LTI2Constants.GRAPH,graph);
				response.setContentType(StandardServices.TOOLSETTINGS_FORMAT);
			} else {
				jsonResponse = theSettings;
				response.setContentType(StandardServices.TOOLSETTINGS_SIMPLE_FORMAT);
			}
			response.setStatus(HttpServletResponse.SC_OK); 
			PrintWriter out = response.getWriter();
System.out.println("jsonResponse="+jsonResponse);
			out.println(jsonResponse.toString());
			return;
		} else if ( "PUT".equals(request.getMethod()) ) {
			// This is assuming the rule that a PUT of the complex settings
			// format that there is only one entry in the graph and it is
			// the same as our current URL.  We parse without much checking.
			String settings = null;
			try {
				JSONArray graph = (JSONArray) requestData.get(LTI2Constants.GRAPH);
				if ( graph.size() != 1 ) {
					response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
					doErrorJSON(request,response, jsonRequest, "Only one graph entry allowed", null);
					return;
				}
				JSONObject firstChild = (JSONObject) graph.get(0);
				JSONObject custom = (JSONObject) firstChild.get(LTI2Constants.CUSTOM);
				settings = custom.toString();
			} catch (Exception e) {
				settings = jsonRequest.getPostBody();
			}

			retval = null;
			if ( SET_LtiLink.equals(scope) ) {
				content.put(LTIService.LTI_SETTINGS, settings);
				retval = ltiService.updateContentDao(contentKey,content,siteId);
			} else if ( SET_ToolProxyBinding.equals(scope) ) {
				if ( proxyBinding != null ) {
					proxyBinding.put(LTIService.LTI_SETTINGS, settings);
					retval = ltiService.updateProxyBindingDao(proxyBindingKey,proxyBinding);
				} else { 
					Properties proxyBindingNew = new Properties();
					proxyBindingNew.setProperty(LTIService.LTI_SITE_ID, siteId);
					proxyBindingNew.setProperty(LTIService.LTI_TOOL_ID, toolKey+"");
					proxyBindingNew.setProperty(LTIService.LTI_SETTINGS, settings);
					retval = ltiService.insertProxyBindingDao(proxyBindingNew);
					M_log.info("inserted ProxyBinding setting="+proxyBindingNew);
				}
			} else if ( SET_ToolProxy.equals(scope) ) {
				deploy.put(LTIService.LTI_SETTINGS, settings);
				retval = ltiService.updateDeployDao(deployKey,deploy);
			}
			if ( retval instanceof String || 
				( retval instanceof Boolean && ((Boolean) retval != Boolean.TRUE) ) ) {
				response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
				doErrorJSON(request,response, jsonRequest, (String) retval, null);
				return;
			}
			response.setStatus(HttpServletResponse.SC_OK);
		} else {
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			doErrorJSON(request,response, jsonRequest, "Method not handled="+request.getMethod(), null);
		}
	}

	public JSONObject parseSettings(String settings)
	{
		if ( settings == null || settings.length() < 1 ) {
			settings = EMPTY_JSON_OBJECT;
		}
		return (JSONObject) JSONValue.parse(settings);
	}

	/* IMS JSON version of Errors */
	public void doErrorJSON(HttpServletRequest request,HttpServletResponse response, 
			IMSJSONRequest json, String message, Exception e) 
		throws java.io.IOException 
		{
			if (e != null) {
				M_log.error(e.getLocalizedMessage(), e);
			}
			M_log.info(message);
			response.setContentType(APPLICATION_JSON);
			Map jsonResponse = new TreeMap();

			Map status = null;
			if ( json == null ) {
				status = IMSJSONRequest.getStatusFailure(message);
			} else {
				status = json.getStatusFailure(message);
				if ( json.base_string != null ) {
					jsonResponse.put("base_string", json.base_string);
				}
			}
			jsonResponse.put(IMSJSONRequest.STATUS, status);
			if ( e != null ) {
				jsonResponse.put("exception", e.getLocalizedMessage());
				try {
					StringWriter sw = new StringWriter();
					PrintWriter pw = new PrintWriter(sw, true);
					e.printStackTrace(pw);
					pw.flush();
					sw.flush();
					jsonResponse.put("traceback", sw.toString() );
				} catch ( Exception f ) {
					jsonResponse.put("traceback", f.getLocalizedMessage());
				}
			}
			String jsonText = JSONValue.toJSONString(jsonResponse);
System.out.print(jsonText);
			PrintWriter out = response.getWriter();
			out.println(jsonText);
		}

	public void destroy() {
	}

}
