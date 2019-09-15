/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.registry.extensions.handlers.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMFactory;
import org.apache.axiom.om.util.AXIOMUtil;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.registry.core.Registry;
import org.wso2.carbon.registry.core.RegistryConstants;
import org.wso2.carbon.registry.core.Resource;
import org.wso2.carbon.registry.core.ResourceImpl;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.registry.core.jdbc.handlers.RequestContext;
import org.wso2.carbon.registry.core.session.CurrentSession;
import org.wso2.carbon.registry.core.utils.RegistryUtils;
import org.wso2.carbon.registry.extensions.services.Utils;
import org.wso2.carbon.registry.extensions.utils.CommonConstants;
import org.wso2.carbon.registry.extensions.utils.CommonUtil;

import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class contains methods to read swagger documents from a given input
 * stream and parse the swagger document in to a JSON object and save the
 * document in to the registry.
 *
 * This class will be initialized from the
 * {@link org.wso2.carbon.registry.extensions.handlers.SwaggerMediaTypeHandler}
 * class when a resource that has a media type of application+swagger+json has
 * to be processed. This class will invoke necessary methods to create a REST
 * Service from the imported swagger definition.
 *
 * @see org.wso2.carbon.registry.extensions.handlers.SwaggerMediaTypeHandler
 * @see org.wso2.carbon.registry.extensions.handlers.utils.RESTServiceUtils
 */
public class SwaggerProcessor {

	private static final Log log = LogFactory.getLog(SwaggerProcessor.class);
	private static final String DEFAULT_TRANSPORT = "http://";
	private static final String DEFAULT_BASE_PATH = "/";

	private RequestContext requestContext;
	private Registry registry;
	private JsonParser parser;
	private String swaggerResourcesPath;
	private String documentVersion;
	private String endpointUrl;
	private List<String> urlList = new ArrayList<>();
	private OMElement restServiceElement = null;
	private OMElement endpointElement = null;
	private String endpointLocation;
	private boolean createRestServiceArtifact;

	public SwaggerProcessor(RequestContext requestContext, boolean createRestServiceArtifact) {
		this.parser = new JsonParser();
		this.requestContext = requestContext;
		this.registry = requestContext.getRegistry();
		this.createRestServiceArtifact = createRestServiceArtifact;
	}

	/**
	 * @return createRestServiceArtifact
	 */
	public boolean isCreateRestServiceArtifact() {
		return createRestServiceArtifact;
	}

	/**
	 * @param createRestServiceArtifact boolean to set createRestServiceArtifact
	 */
	public void setCreateRestServiceArtifact(boolean createRestServiceArtifact) {
		this.createRestServiceArtifact = createRestServiceArtifact;
	}

	/**
	 * Check if the given openapi version is in 3.0.x format
	 *
	 * @param swaggerVersion
	 * @return
	 */
	public static boolean isValidOpenApiVersion(String swaggerVersion) {
		Pattern openApi3Pattern = Pattern.compile(SwaggerConstants.OPEN_API_3_ALLOWED_VERSION);
		Matcher openApi3Version = openApi3Pattern.matcher(swaggerVersion);
		return openApi3Version.find();
	}

	/**
	 * Get all the keys in servers json object
	 *
	 * @param serverVariables
	 * @return List<String>
	 */
	public static List<String> getAllKeys(JsonObject serverVariables) {
		List<String> keyList = new ArrayList<>();
		Set<Map.Entry<String, JsonElement>> entries = serverVariables.entrySet();
		for (Map.Entry<String, JsonElement> entry : entries) {
			keyList.add(entry.getKey());
		}
		return keyList;
	}

	/**
	 * Get the pattern of variable in a given OpenAPI url
	 *
	 * @param key	The name of the variable
	 * @return String
	 */
	private String getPatternForVariableMapping(String key) {
		return "{" + key + "}";
	}

	/**
	 * For each of the variable keys in the servers object, get the enum values and replace them in the original
	 * url and create a list of proper urls
	 * Eg: "url": "{scheme}://developer.gov/{base}"
	 *
	 * @param variableObject 	The object that contains the variables to be replaced in the url
	 * @param keyList 			The list of keys in the variable object
	 * @param initialUrl		The url to be replaced
	 * @return List<String>
	 */
	private List<String> getServerUrlEndpoint(JsonObject variableObject, List<String> keyList, String initialUrl) {
		// CopyOnWriteArrayList is used here because the same ulr list is being updated
		List<String> replacedUrlList = new CopyOnWriteArrayList<>();
		String replacedUrl;

		// To iterate through the list of keys (mapping variable) in the given url
		for (String key : keyList) {
			String pattern = getPatternForVariableMapping(key);
			JsonObject keyObject = variableObject.get(key).getAsJsonObject();

			JsonArray enumValues = keyObject.has(SwaggerConstants.ENUM) ? keyObject.get(SwaggerConstants.ENUM).getAsJsonArray() : null;
			String defaultValue = null;

			// Check if the enum array is empty
			if ((enumValues == null) || (enumValues.size() == 0)) {
				// If empty assign the default value
				defaultValue = keyObject.get(SwaggerConstants.DEFAULT).getAsString();
			}

			// Check if it's the 1st iteration and the url has the pattern and add to the list
			if (replacedUrlList.isEmpty() && initialUrl.contains(pattern)) {
				if (defaultValue != null) {
					replacedUrl = initialUrl.replace(pattern, defaultValue);
					replacedUrlList.add(replacedUrl);
				} else {
					for (JsonElement value : enumValues) {
						replacedUrl = initialUrl.replace(pattern, value.getAsString());
						replacedUrlList.add(replacedUrl);
					}
				}
			} else {
				for (String existingUrl : replacedUrlList) {
					if (existingUrl.contains(pattern)) {
						if (defaultValue != null) {
							replacedUrl = existingUrl.replace(pattern, defaultValue);
							replacedUrlList.remove(existingUrl);
							replacedUrlList.add(replacedUrl);
						} else {
							for (JsonElement value : enumValues) {
								replacedUrl = existingUrl.replace(pattern, value.getAsString());
								replacedUrlList.remove(existingUrl);
								replacedUrlList.add(replacedUrl);
							}
						}

					}
				}
			}
		}
		return replacedUrlList;
	}

	/**
	 * Saves the swagger file as a registry artifact.
	 *
	 * @param inputStream    input stream to read content.
	 * @param commonLocation root location of the swagger artifacts.
	 * @param sourceUrl      source URL.
	 * @return swagger resource path.
	 * @throws RegistryException If a failure occurs when adding the swagger to
	 *                           registry.
	 */
	public String processSwagger(InputStream inputStream, String commonLocation, String sourceUrl)
			throws RegistryException {
		// create a collection if not exists.
		createCollection(commonLocation);

		// Reading resource content and content details.
		ByteArrayOutputStream swaggerContentStream = CommonUtil.readSourceContent(inputStream);
		JsonObject swaggerDocObject = getSwaggerObject(swaggerContentStream.toString());
		String swaggerVersion = getSwaggerVersion(swaggerDocObject);
		documentVersion = requestContext.getResource().getProperty(RegistryConstants.VERSION_PARAMETER_NAME);
		if (documentVersion == null) {
			documentVersion = CommonConstants.SWAGGER_DOC_VERSION_DEFAULT_VALUE;
			requestContext.getResource().setProperty(RegistryConstants.VERSION_PARAMETER_NAME, documentVersion);
		}
		String swaggerResourcePath = getSwaggerDocumentPath(commonLocation, swaggerDocObject);

		/*
		 * Switches from the swagger version and process document adding process and the
		 * REST Service creation process using the relevant documents.
		 */
		if (SwaggerConstants.SWAGGER_VERSION_12.equals(swaggerVersion)) {
			if (addSwaggerDocumentToRegistry(swaggerContentStream, swaggerResourcePath, documentVersion)) {
				List<JsonObject> resourceObjects = addResourceDocsToRegistry(swaggerDocObject, sourceUrl,
						swaggerResourcePath);
				if (isCreateRestServiceArtifact()) {
					restServiceElement = (resourceObjects != null)
							? RESTServiceUtils.createRestServiceArtifact(swaggerDocObject, swaggerVersion, endpointUrl,
							resourceObjects, swaggerResourcePath, documentVersion)
							: null;
				}
			} else {
				return null;
			}

		} else if (SwaggerConstants.SWAGGER_VERSION_2.equals(swaggerVersion)) {
			if (addSwaggerDocumentToRegistry(swaggerContentStream, swaggerResourcePath, documentVersion)) {
				createEndpointElement(swaggerDocObject, swaggerVersion);
				if (isCreateRestServiceArtifact()) {
					restServiceElement = RESTServiceUtils
							.createRestServiceArtifact(swaggerDocObject, swaggerVersion, endpointUrl, null,
									swaggerResourcePath, documentVersion);
				}
			} else {
				return null;
			}

		} else if (isValidOpenApiVersion(swaggerVersion)) {
			if (addSwaggerDocumentToRegistry(swaggerContentStream, swaggerResourcePath, documentVersion)) {
				List<String> urls = createEndpointElementList(swaggerDocObject);
				if (isCreateRestServiceArtifact()) {
					restServiceElement = RESTServiceUtils.createRestServiceArtifactForOpenApi(swaggerDocObject, urls, swaggerResourcePath, documentVersion);

				} else {
					log.warn("Failed to create Rest Service Artifact with Document Version " + documentVersion);

				}
			} else {
				log.warn("Failed to Add Swagger Document to Registry. Unsupported Swagger Version: " + swaggerVersion);
				return null;
			}
		}

		/*
		 * If REST Service content is not empty and createRestServiceArtifact is true,
		 * saves the REST service and adds the relevant associations.
		 */
		if (isCreateRestServiceArtifact()) {
			if (restServiceElement != null) {
				String servicePath = RESTServiceUtils.addServiceToRegistry(requestContext, restServiceElement);
				registry.addAssociation(servicePath, swaggerResourcePath, CommonConstants.DEPENDS);
				registry.addAssociation(swaggerResourcePath, servicePath, CommonConstants.USED_BY);

				if (isValidOpenApiVersion(swaggerVersion)) {
					saveEndpointElementList(servicePath, urlList);
				} else
					saveEndpointElement(servicePath);
			} else {
				log.warn("Service content is null. Cannot create the REST Service artifact.");
			}
		}

		CommonUtil.closeOutputStream(swaggerContentStream);

		return swaggerResourcePath;
	}

	/**
	 * Save endpoint element to the registry.
	 *
	 * @param servicePath service path.
	 * @throws RegistryException If fails to save the endpoint.
	 */
	public void saveEndpointElement(String servicePath) throws RegistryException {
		String endpointPath;
		if (StringUtils.isNotBlank(endpointUrl)) {
			EndpointUtils.addEndpointToService(requestContext.getRegistry(), servicePath, endpointUrl, "");
			endpointPath = RESTServiceUtils.addEndpointToRegistry(requestContext, endpointElement, endpointLocation);
			CommonUtil.addDependency(registry, servicePath, endpointPath);
		}
	}

	/**
	 * Save endpoint elements to the registry
	 *
	 * @param servicePath
	 * @param urlList
	 * @throws RegistryException
	 */
	public void saveEndpointElementList(String servicePath, List<String> urlList) throws RegistryException {

		for(String url : urlList){
			String endpointPath;
			if (StringUtils.isNotBlank(url)) {
				EndpointUtils.addEndpointToService(requestContext.getRegistry(), servicePath, url, "");
				endpointPath = RESTServiceUtils.addEndpointToRegistry(requestContext, endpointElement, endpointLocation);
				CommonUtil.addDependency(registry, servicePath, endpointPath);
			}
		}
	}

	/**
	 * Saves a swagger document in the registry.
	 *
	 * @param contentStream   resource content.
	 * @param path            resource path.
	 * @param documentVersion version of the swagger document.
	 * @throws RegistryException If fails to add the swagger document to registry.
	 */
	private boolean addSwaggerDocumentToRegistry(ByteArrayOutputStream contentStream, String path,
												 String documentVersion) throws RegistryException {
		Resource resource;
		/*
		 * Checks if a resource is already exists in the given path. If exists, Compare
		 * resource contents and if updated, updates the document, if not skip the
		 * updating process If not exists, Creates a new resource and add to the
		 * resource path.
		 */
		if (registry.resourceExists(path)) {
			resource = registry.get(path);
			Object resourceContentObj = resource.getContent();
			String resourceContent;
			if (resourceContentObj instanceof String) {
				resourceContent = (String) resourceContentObj;
				resource.setContent(RegistryUtils.encodeString(resourceContent));
			} else if (resourceContentObj instanceof byte[]) {
				resourceContent = RegistryUtils.decodeBytes((byte[]) resourceContentObj);
			} else {
				throw new RegistryException(CommonConstants.INVALID_CONTENT);
			}
			if (resourceContent.equals(contentStream.toString())) {
				if (log.isDebugEnabled()) {
					log.debug("Old content is same as the new content. Skipping the put action.");
				}
				return true;
			}
		} else {
			// If a resource does not exist in the given path.
			resource = new ResourceImpl();
		}

		String resourceId = (resource.getUUID() == null) ? UUID.randomUUID().toString() : resource.getUUID();

		resource.setUUID(resourceId);
		resource.setMediaType(CommonConstants.SWAGGER_MEDIA_TYPE);
		resource.setContent(contentStream.toByteArray());
		resource.addProperty(RegistryConstants.VERSION_PARAMETER_NAME, documentVersion);
		CommonUtil.copyProperties(this.requestContext.getResource(), resource);
		registry.put(path, resource);

		return true;
	}

	/**
	 * Creates a collection in the given common location.
	 *
	 * @param commonLocation location to create the collection.
	 * @throws RegistryException If fails to create a collection at given location.
	 */
	private void createCollection(String commonLocation) throws RegistryException {
		Registry systemRegistry = CommonUtil.getUnchrootedSystemRegistry(requestContext);
		// Creating a collection if not exists.
		if (!systemRegistry.resourceExists(commonLocation)) {
			systemRegistry.put(commonLocation, systemRegistry.newCollection());
		}
	}

	/**
	 * Adds swagger 1.2 api resource documents to registry and returns a list of
	 * resource documents as JSON objects.
	 *
	 * @param swaggerDocObject swagger document JSON object.
	 * @param sourceUrl        source url of the swagger document.
	 * @param swaggerDocPath   swagger document path. (path of the registry)
	 * @return List of api resources.
	 * @throws RegistryException If fails to import or save resource docs to the
	 *                           registry.
	 */
	private List<JsonObject> addResourceDocsToRegistry(JsonObject swaggerDocObject, String sourceUrl,
													   String swaggerDocPath) throws RegistryException {

		if (sourceUrl == null) {
			log.debug(CommonConstants.EMPTY_URL);
			log.warn("Resource paths cannot be read. Creating the REST service might fail.");
			return null;
		} else if (sourceUrl.startsWith("file")) {
			sourceUrl = sourceUrl.substring(0, sourceUrl.lastIndexOf("/"));
		}

		List<JsonObject> resourceObjects = new ArrayList<>();
		// Adding Resource documents to registry.
		JsonArray pathResources = swaggerDocObject.get(SwaggerConstants.APIS).getAsJsonArray();
		ByteArrayOutputStream resourceContentStream = null;
		InputStream resourceInputStream = null;
		String path;

		/*
		 * Loops through apis array of the swagger 1.2 api-doc and reads all the
		 * resource documents and saves them in to the registry.
		 */
		for (JsonElement pathResource : pathResources) {
			JsonObject resourceObj = pathResource.getAsJsonObject();
			path = resourceObj.get(SwaggerConstants.PATH).getAsString();
			try {
				resourceInputStream = new URL(sourceUrl + path).openStream();
			} catch (IOException e) {
				throw new RegistryException("The URL " + sourceUrl + path + " is incorrect.", e);
			}
			resourceContentStream = CommonUtil.readSourceContent(resourceInputStream);
			JsonObject resourceObject = parser.parse(resourceContentStream.toString()).getAsJsonObject();
			resourceObjects.add(resourceObject);
			if (endpointElement == null) {
				createEndpointElement(resourceObject, SwaggerConstants.SWAGGER_VERSION_12);
			}
			// path = swaggerResourcesPath + path;
			path = path.replace("/", "");
			path = CommonUtil.replaceExpressionOfPath(swaggerResourcesPath, "name", path);
			path = RegistryUtils.getAbsolutePath(registry.getRegistryContext(), path);
			// Save Resource document to registry
			if (addSwaggerDocumentToRegistry(resourceContentStream, path, documentVersion)) {
				// Adding an dependency to API_DOC
				registry.addAssociation(swaggerDocPath, path, CommonConstants.DEPENDS);
			}
		}

		CommonUtil.closeOutputStream(resourceContentStream);
		CommonUtil.closeInputStream(resourceInputStream);
		return resourceObjects;
	}

	/**
	 * Generates the service endpoint element from the swagger object.
	 *
	 * @param swaggerObject  swagger document object.
	 * @param swaggerVersion swagger version.
	 */
	private void createEndpointElement(JsonObject swaggerObject, String swaggerVersion) throws RegistryException {
		/*
		 * Extracting endpoint url from the swagger document.
		 */
		if (SwaggerConstants.SWAGGER_VERSION_12.equals(swaggerVersion)) {
			JsonElement endpointUrlElement = swaggerObject.get(SwaggerConstants.BASE_PATH);
			if (endpointUrlElement == null) {
				log.warn("Endpoint url is not specified in the swagger document. Endpoint creation might fail. ");
				return;
			} else {
				endpointUrl = endpointUrlElement.getAsString();
			}
		} else if (SwaggerConstants.SWAGGER_VERSION_2.equals(swaggerVersion)) {
			JsonElement transportsElement = swaggerObject.get(SwaggerConstants.SCHEMES);
			JsonArray transports = (transportsElement != null) ? transportsElement.getAsJsonArray() : null;
			String transport = (transports != null) ? transports.get(0).getAsString() + "://" : DEFAULT_TRANSPORT;
			JsonElement hostElement = swaggerObject.get(SwaggerConstants.HOST);
			String host = (hostElement != null) ? hostElement.getAsString() : null;
			if (host == null) {
				log.warn("Endpoint(host) url is not specified in the swagger document. "
						+ "The host serving the documentation is to be used(including the port) as endpoint host");

				if (requestContext.getSourceURL() != null) {
					URL sourceURL = null;
					try {
						sourceURL = new URL(requestContext.getSourceURL());
					} catch (MalformedURLException e) {
						throw new RegistryException("Error in parsing the source URL. ", e);
					}
					host = sourceURL.getAuthority();
				}
			}

			if (host == null) {
				log.warn("Can't derive the endpoint(host) url when uploading swagger from file. "
						+ "Endpoint creation might fail. ");
				return;
			}

			JsonElement basePathElement = swaggerObject.get(SwaggerConstants.BASE_PATH);
			String basePath = (basePathElement != null) ? basePathElement.getAsString() : DEFAULT_BASE_PATH;

			endpointUrl = transport + host + basePath;

		}
		/*
		 * Creating endpoint artifact changed
		 */
		OMFactory factory = OMAbstractFactory.getOMFactory();
		endpointLocation = EndpointUtils.deriveEndpointFromUrl(endpointUrl);

		// CHANGED: the location name
		String endpointName = EndpointUtils.deriveEndpointNameWithNamespaceFromUrl(endpointUrl).replaceAll("\"", "");
		String endpointContent = EndpointUtils.getEndpointContentWithOverview(endpointUrl, endpointLocation, endpointName, documentVersion);

		try {
			endpointElement = AXIOMUtil.stringToOM(factory, endpointContent);
		} catch (XMLStreamException e) {
			throw new RegistryException("Error in creating the endpoint element. ", e);
		}

	}

	/**
	 *
	 * Generate endpoint url list for a given OpenAPI 3.0.x version with different cases for "servers" element
	 *
	 * @param swaggerObject
	 * @return List<String>
	 * @throws RegistryException
	 */
	private List<String> createEndpointElementList(JsonObject swaggerObject) throws RegistryException {
		if (swaggerObject.has(SwaggerConstants.SERVERS)) {
			JsonElement serversElement = swaggerObject.get(SwaggerConstants.SERVERS);
			JsonArray servers = serversElement.getAsJsonArray();
			int serversObjectSize = servers.size();

			if (serversObjectSize > 0) {
				for(int i=0; i<serversObjectSize; i++){
					JsonObject serverObject = servers.get(i).getAsJsonObject();
					String url = serverObject.get(SwaggerConstants.URL).toString().replace("\"", "");

					// case 3: url: {}, <key1>, <key2>, ...
					if (serverObject.has(SwaggerConstants.VARIABLES)) {
						JsonObject variablesObject = serverObject.get(SwaggerConstants.VARIABLES).getAsJsonObject();
						List<String> keyList = getAllKeys(variablesObject);
						List<String> serverUrlEndpointsList = getServerUrlEndpoint(variablesObject, keyList, url);

						for(String serverUrl : serverUrlEndpointsList){
							urlList.add(serverUrl);
						}
					} else {
						//case 1
						endpointUrl = url;
						urlList.add(endpointUrl);
					}

				}
				// case 2: servers:[]
			} else {
				// If the servers property is not provided, or is an empty array, the default value would be a
				// Server Object with a url value of "/"
				endpointUrl = SwaggerConstants.DEFAULT_URL;

			}
			//case 4: no server tag
		} else {
			endpointUrl = SwaggerConstants.DEFAULT_URL;
		}


		/*
		 * Creating endpoint artifact changed
		 */
		for(String urlValue : urlList){
			OMFactory factory = OMAbstractFactory.getOMFactory();
			endpointLocation =EndpointUtils.deriveEndpointFromUrl(urlValue);

			// CHANGED: the location name
			String endpointName = EndpointUtils.deriveEndpointNameWithNamespaceFromUrl(urlValue).replaceAll("\"", "");
			String endpointContent = EndpointUtils.getEndpointContentWithOverview(urlValue, endpointLocation, endpointName, documentVersion);
			try

			{
				endpointElement = AXIOMUtil.stringToOM(factory, endpointContent);
			} catch(
					XMLStreamException e)

			{
				throw new RegistryException("Error in creating the endpoint element. ", e);
			}
		}
		return urlList;
	}

	private void handleEmptyArrayForServersKey() {
	}

	/**
	 * Configures the swagger resource path form its content and returns the swagger
	 * document path.
	 *
	 * @param rootLocation root location of the swagger files.
	 * @param content      swagger content.
	 * @return Common resource path.
	 */
	private String getSwaggerDocumentPath(String rootLocation, JsonObject content) throws RegistryException {

		String swaggerDocPath = requestContext.getResourcePath().getPath();
		String swaggerDocName = swaggerDocPath
				.substring(swaggerDocPath.lastIndexOf(RegistryConstants.PATH_SEPARATOR) + 1);
		JsonElement infoElement = content.get(SwaggerConstants.INFO);
		JsonObject infoObject = (infoElement != null) ? infoElement.getAsJsonObject() : null;

		if (infoObject == null || infoElement.isJsonNull()) {
			throw new RegistryException("Invalid swagger document.");
		}
		String serviceName = infoObject.get(SwaggerConstants.TITLE).getAsString().replaceAll("\\s", "");
		String serviceProvider = CarbonContext.getThreadLocalCarbonContext().getUsername();

		swaggerResourcesPath = rootLocation + serviceProvider + RegistryConstants.PATH_SEPARATOR + serviceName
				+ RegistryConstants.PATH_SEPARATOR + documentVersion;

		String pathExpression = getSwaggerRegistryPath(swaggerDocName, serviceProvider);

		return RegistryUtils.getAbsolutePath(registry.getRegistryContext(), pathExpression);
	}

	private String getSwaggerRegistryPath(String swaggerDocName, String serviceProvider) {
		String pathExpression = Utils.getRxtService().getStoragePath(CommonConstants.SWAGGER_MEDIA_TYPE);
		pathExpression = CommonUtil.getPathFromPathExpression(pathExpression,
				requestContext.getResource().getProperties(), null);
		pathExpression = CommonUtil.replaceExpressionOfPath(pathExpression, "provider", serviceProvider);
		swaggerResourcesPath = pathExpression;
		pathExpression = CommonUtil.replaceExpressionOfPath(pathExpression, "name", swaggerDocName);
		String swaggerPath = pathExpression;
		/**
		 * Fix for the REGISTRY-3052 : validation is to check the whether this invoked
		 * by ZIPWSDLMediaTypeHandler Setting the registry and absolute paths to current
		 * session to avoid incorrect resource path entry in REG_LOG table
		 */
		if (CurrentSession.getLocalPathMap() != null
				&& !Boolean.valueOf(CurrentSession.getLocalPathMap().get(CommonConstants.ARCHIEVE_UPLOAD))) {
			swaggerPath = CommonUtil.getRegistryPath(requestContext.getRegistry().getRegistryContext(), pathExpression);
			if (log.isDebugEnabled()) {
				log.debug("Saving current session local paths, key: " + swaggerPath + " | value: " + pathExpression);
			}
			CurrentSession.getLocalPathMap().put(swaggerPath, pathExpression);
		}
		return swaggerPath;
	}

	/**
	 * Parses the swagger content and return as a JsonObject
	 *
	 * @param swaggerContent content as a String.
	 * @return Swagger document as a JSON Object.
	 * @throws RegistryException If fails to parse the swagger document.
	 */
	private JsonObject getSwaggerObject(String swaggerContent) throws RegistryException {
		JsonElement swaggerElement = parser.parse(swaggerContent);

		if (swaggerElement == null || swaggerElement.isJsonNull()) {
			throw new RegistryException("Unexpected error occurred when parsing the swagger content.");
		} else {
			return swaggerElement.getAsJsonObject();
		}
	}

	/**
	 * Returns swagger version
	 *
	 * @param swaggerDocObject swagger JSON.
	 * @return Swagger version.
	 * @throws RegistryException If swagger version is unsupported.
	 */
	private String getSwaggerVersion(JsonObject swaggerDocObject) throws RegistryException {
		// Getting the swagger version of swagger definition
		JsonElement swaggerVersionElement = swaggerDocObject.get(SwaggerConstants.SWAGGER_VERSION_KEY);

		if (swaggerVersionElement == null) {
			swaggerVersionElement = swaggerDocObject.get(SwaggerConstants.SWAGGER2_VERSION_KEY);
				if(swaggerVersionElement == null){
					if(isValidOpenApiVersion(swaggerDocObject.get(SwaggerConstants.SWAGGER3_VERSION_KEY).getAsString()))
						swaggerVersionElement = swaggerDocObject.get(SwaggerConstants.SWAGGER3_VERSION_KEY);
				}
		}
		if (swaggerVersionElement == null) {
			log.info("Swagger version: " + swaggerVersionElement);
			throw new RegistryException("Unsupported swagger version.");
		}

		return swaggerVersionElement.getAsString();
	}
}