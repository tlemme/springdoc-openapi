/*
 *
 *  *
 *  *  *
 *  *  *  * Copyright 2019-2022 the original author or authors.
 *  *  *  *
 *  *  *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  *  *  * you may not use this file except in compliance with the License.
 *  *  *  * You may obtain a copy of the License at
 *  *  *  *
 *  *  *  *      https://www.apache.org/licenses/LICENSE-2.0
 *  *  *  *
 *  *  *  * Unless required by applicable law or agreed to in writing, software
 *  *  *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  *  *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  *  * See the License for the specific language governing permissions and
 *  *  *  * limitations under the License.
 *  *  *
 *  *
 *
 */

package org.springdoc.api;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature;
import io.swagger.v3.core.filter.SpecFilter;
import io.swagger.v3.core.util.ReflectionUtils;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.callbacks.Callback;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.core.AbstractRequestService;
import org.springdoc.core.GenericParameterService;
import org.springdoc.core.GenericResponseService;
import org.springdoc.core.MethodAttributes;
import org.springdoc.core.OpenAPIService;
import org.springdoc.core.OperationService;
import org.springdoc.core.SpringDocConfigProperties;
import org.springdoc.core.SpringDocConfigProperties.ApiDocs.OpenApiVersion;
import org.springdoc.core.SpringDocConfigProperties.GroupConfig;
import org.springdoc.core.SpringDocProviders;
import org.springdoc.core.annotations.RouterOperations;
import org.springdoc.core.customizers.DataRestRouterOperationCustomizer;
import org.springdoc.core.customizers.OpenApiLocaleCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.customizers.RouterOperationCustomizer;
import org.springdoc.core.customizers.SpringDocCustomizers;
import org.springdoc.core.fn.AbstractRouterFunctionVisitor;
import org.springdoc.core.fn.RouterFunctionData;
import org.springdoc.core.fn.RouterOperation;
import org.springdoc.core.providers.ActuatorProvider;
import org.springdoc.core.providers.CloudFunctionProvider;
import org.springdoc.core.providers.JavadocProvider;
import org.springdoc.core.providers.ObjectMapperProvider;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.Environment;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.HandlerMethod;

import static org.springdoc.core.Constants.ACTUATOR_DEFAULT_GROUP;
import static org.springdoc.core.Constants.DOT;
import static org.springdoc.core.Constants.OPERATION_ATTRIBUTE;
import static org.springdoc.core.Constants.SPRING_MVC_SERVLET_PATH;
import static org.springdoc.core.converters.SchemaPropertyDeprecatingConverter.isDeprecated;
import static org.springframework.util.AntPathMatcher.DEFAULT_PATH_SEPARATOR;

/**
 * The type Abstract open api resource.
 * @author bnasslahsen
 * @author kevinraddatz
 * @author hyeonisism
 */
public abstract class AbstractOpenApiResource extends SpecFilter {

	/**
	 * The constant LOGGER.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(AbstractOpenApiResource.class);

	/**
	 * The constant ADDITIONAL_REST_CONTROLLERS.
	 */
	private static final Set<Class<?>> ADDITIONAL_REST_CONTROLLERS = new CopyOnWriteArraySet<>();

	/**
	 * The constant HIDDEN_REST_CONTROLLERS.
	 */
	private static final Set<Class<?>> HIDDEN_REST_CONTROLLERS = new CopyOnWriteArraySet<>();

	/**
	 * The constant MODEL_AND_VIEW_CLASS.
	 */
	private static Class<?> modelAndViewClass;

	/**
	 * The Spring doc config properties.
	 */
	protected final SpringDocConfigProperties springDocConfigProperties;

	/**
	 * The Group name.
	 */
	protected final String groupName;

	/**
	 * The Spring doc providers.
	 */
	protected final SpringDocProviders springDocProviders;

	/**
	 * The open api builder object factory.
	 */
	private final ObjectFactory<OpenAPIService> openAPIBuilderObjectFactory;

	/**
	 * The Request builder.
	 */
	private final AbstractRequestService requestBuilder;

	/**
	 * The Response builder.
	 */
	private final GenericResponseService responseBuilder;

	/**
	 * The Operation parser.
	 */
	private final OperationService operationParser;

	/**
	 * The Ant path matcher.
	 */
	private final AntPathMatcher antPathMatcher = new AntPathMatcher();


	/**
	 * The Open api builder.
	 */
	protected OpenAPIService openAPIService;

	/**
	 * The Spring doc customizers.
	 */
	protected final SpringDocCustomizers springDocCustomizers;

	/**
	 * Instantiates a new Abstract open api resource.
	 *
	 * @param groupName the group name
	 * @param openAPIBuilderObjectFactory the open api builder object factory
	 * @param requestBuilder the request builder
	 * @param responseBuilder the response builder
	 * @param operationParser the operation parser
	 * @param springDocConfigProperties the spring doc config properties
	 * @param springDocProviders the spring doc providers
	 * @param springDocCustomizers the spring doc customizers
	 */
	protected AbstractOpenApiResource(String groupName, ObjectFactory<OpenAPIService> openAPIBuilderObjectFactory,
			AbstractRequestService requestBuilder,
			GenericResponseService responseBuilder, OperationService operationParser,
			SpringDocConfigProperties springDocConfigProperties,
			SpringDocProviders springDocProviders, SpringDocCustomizers springDocCustomizers) {
		super();
		this.groupName = Objects.requireNonNull(groupName, "groupName");
		this.openAPIBuilderObjectFactory = openAPIBuilderObjectFactory;
		this.openAPIService = openAPIBuilderObjectFactory.getObject();
		this.requestBuilder = requestBuilder;
		this.responseBuilder = responseBuilder;
		this.operationParser = operationParser;
		this.springDocProviders = springDocProviders;
		this.springDocConfigProperties = springDocConfigProperties;
		this.springDocCustomizers=springDocCustomizers;
		if (springDocConfigProperties.isPreLoadingEnabled())
			Executors.newSingleThreadExecutor().execute(this::getOpenApi);
	}

	/**
	 * Add rest controllers.
	 *
	 * @param classes the classes
	 */
	public static void addRestControllers(Class<?>... classes) {
		ADDITIONAL_REST_CONTROLLERS.addAll(Arrays.asList(classes));
	}

	/**
	 * Add hidden rest controllers.
	 *
	 * @param classes the classes
	 */
	public static void addHiddenRestControllers(Class<?>... classes) {
		HIDDEN_REST_CONTROLLERS.addAll(Arrays.asList(classes));
	}

	/**
	 * Add hidden rest controllers.
	 *
	 * @param classes the classes
	 */
	public static void addHiddenRestControllers(String... classes) {
		Set<Class<?>> hiddenClasses = new HashSet<>();
		for (String aClass : classes) {
			try {
				hiddenClasses.add(Class.forName(aClass));
			}
			catch (ClassNotFoundException e) {
				LOGGER.warn("The following class doesn't exist and cannot be hidden: {}", aClass);
			}
		}
		HIDDEN_REST_CONTROLLERS.addAll(hiddenClasses);
	}

	/**
	 * Contains response body boolean.
	 *
	 * @param handlerMethod the handler method
	 * @return the boolean
	 */
	public static boolean containsResponseBody(HandlerMethod handlerMethod) {
		ResponseBody responseBodyAnnotation = AnnotationUtils.findAnnotation(handlerMethod.getBeanType(), ResponseBody.class);
		if (responseBodyAnnotation == null)
			responseBodyAnnotation = AnnotationUtils.findAnnotation(handlerMethod.getMethod(), ResponseBody.class);
		return responseBodyAnnotation != null;
	}

	/**
	 * Is hidden rest controllers boolean.
	 *
	 * @param rawClass the raw class
	 * @return the boolean
	 */
	public static boolean isHiddenRestControllers(Class<?> rawClass) {
		return HIDDEN_REST_CONTROLLERS.stream().anyMatch(clazz -> clazz.isAssignableFrom(rawClass));
	}

	/**
	 * Sets model and view class.
	 *
	 * @param modelAndViewClass the model and view class
	 */
	public static void setModelAndViewClass(Class<?> modelAndViewClass) {
		AbstractOpenApiResource.modelAndViewClass = modelAndViewClass;
	}

	/**
	 * Gets open api.
	 */
	private void getOpenApi() {
		this.getOpenApi(Locale.getDefault());
	}

	/**
	 * Gets open api.
	 * @param locale the locale
	 * @return the open api
	 */
	protected synchronized OpenAPI getOpenApi(Locale locale) {
		final OpenAPI openAPI;
		final Locale finalLocale = locale == null ? Locale.getDefault() : locale;
		if (openAPIService.getCachedOpenAPI(finalLocale) == null || springDocConfigProperties.isCacheDisabled()) {
			Instant start = Instant.now();
			openAPI = openAPIService.build(finalLocale);
			Map<String, Object> mappingsMap = openAPIService.getMappingsMap().entrySet().stream()
					.filter(controller -> (AnnotationUtils.findAnnotation(controller.getValue().getClass(),
							Hidden.class) == null))
					.filter(controller -> !AbstractOpenApiResource.isHiddenRestControllers(controller.getValue().getClass()))
					.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a1, a2) -> a1));

			Map<String, Object> findControllerAdvice = openAPIService.getControllerAdviceMap();
			if (OpenApiVersion.OPENAPI_3_1 == springDocConfigProperties.getApiDocs().getVersion())
				openAPI.openapi(OpenApiVersion.OPENAPI_3_1.getVersion());
			if (springDocConfigProperties.isDefaultOverrideWithGenericResponse()) {
				if (!CollectionUtils.isEmpty(mappingsMap))
					findControllerAdvice.putAll(mappingsMap);
				responseBuilder.buildGenericResponse(openAPI.getComponents(), findControllerAdvice, finalLocale);
			}
			getPaths(mappingsMap, finalLocale, openAPI);

			Optional<CloudFunctionProvider> cloudFunctionProviderOptional = springDocProviders.getSpringCloudFunctionProvider();
			cloudFunctionProviderOptional.ifPresent(cloudFunctionProvider -> {
						List<RouterOperation> routerOperationList = cloudFunctionProvider.getRouterOperations(openAPI);
						if (!CollectionUtils.isEmpty(routerOperationList))
							this.calculatePath(routerOperationList, locale, openAPI);
					}
			);
			if (!CollectionUtils.isEmpty(openAPI.getServers()))
				openAPIService.setServersPresent(true);
			else
				openAPIService.setServersPresent(false);
			openAPIService.updateServers(openAPI);

			if (springDocConfigProperties.isRemoveBrokenReferenceDefinitions())
				this.removeBrokenReferenceDefinitions(openAPI);

			// run the optional customisers
			List<Server> servers = openAPI.getServers();
			List<Server> serversCopy = null;
			try {
				serversCopy = springDocProviders.jsonMapper()
						.readValue(springDocProviders.jsonMapper().writeValueAsString(servers), new TypeReference<List<Server>>() {});
			}
			catch (JsonProcessingException e) {
				LOGGER.warn("Json Processing Exception occurred: {}", e.getMessage());
			}

			openAPIService.getContext().getBeansOfType(OpenApiLocaleCustomizer.class).values().forEach(openApiLocaleCustomizer -> openApiLocaleCustomizer.customise(openAPI, finalLocale));
			springDocCustomizers.getOpenApiCustomizers().ifPresent(apiCustomisers -> apiCustomisers.forEach(openApiCustomiser -> openApiCustomiser.customise(openAPI)));
			if (!CollectionUtils.isEmpty(openAPI.getServers()) && !openAPI.getServers().equals(serversCopy))
				openAPIService.setServersPresent(true);

			openAPIService.setCachedOpenAPI(openAPI, finalLocale);

			LOGGER.info("Init duration for springdoc-openapi is: {} ms",
					Duration.between(start, Instant.now()).toMillis());
		}
		else {
			LOGGER.debug("Fetching openApi document from cache");
			openAPI = openAPIService.getCachedOpenAPI(finalLocale);
			openAPIService.updateServers(openAPI);
		}

		return openAPI;
	}

	/**
	 * Gets paths.
	 *
	 * @param findRestControllers the find rest controllers
	 * @param locale the locale
	 * @param openAPI the open api
	 */
	protected abstract void getPaths(Map<String, Object> findRestControllers, Locale locale, OpenAPI openAPI);

	/**
	 * Calculate path.
	 *
	 * @param handlerMethod the handler method
	 * @param routerOperation the router operation
	 * @param locale the locale
	 * @param openAPI the open api
	 */
	protected void calculatePath(HandlerMethod handlerMethod,
			RouterOperation routerOperation, Locale locale, OpenAPI openAPI) {

		routerOperation = customizeRouterOperation(routerOperation, handlerMethod);

		String operationPath = routerOperation.getPath();
		Set<RequestMethod> requestMethods = new TreeSet<>(Arrays.asList(routerOperation.getMethods()));
		io.swagger.v3.oas.annotations.Operation apiOperation = routerOperation.getOperation();
		String[] methodConsumes = routerOperation.getConsumes();
		String[] methodProduces = routerOperation.getProduces();
		String[] headers = routerOperation.getHeaders();
		Map<String, String> queryParams = routerOperation.getQueryParams();

		Components components = openAPI.getComponents();
		Paths paths = openAPI.getPaths();

		Map<HttpMethod, Operation> operationMap = null;
		if (paths.containsKey(operationPath)) {
			PathItem pathItem = paths.get(operationPath);
			operationMap = pathItem.readOperationsMap();
		}

		JavadocProvider javadocProvider = operationParser.getJavadocProvider();

		for (RequestMethod requestMethod : requestMethods) {
			Operation existingOperation = getExistingOperation(operationMap, requestMethod);
			Method method = handlerMethod.getMethod();
			// skip hidden operations
			if (operationParser.isHidden(method))
				continue;

			RequestMapping reqMappingClass = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(),
					RequestMapping.class);

			MethodAttributes methodAttributes = new MethodAttributes(springDocConfigProperties.getDefaultConsumesMediaType(), springDocConfigProperties.getDefaultProducesMediaType(), methodConsumes, methodProduces, headers, locale);
			methodAttributes.setMethodOverloaded(existingOperation != null);
			//Use the javadoc return if present
			if (javadocProvider != null) {
				methodAttributes.setJavadocReturn(javadocProvider.getMethodJavadocReturn(handlerMethod.getMethod()));
			}

			if (reqMappingClass != null) {
				methodAttributes.setClassConsumes(reqMappingClass.consumes());
				methodAttributes.setClassProduces(reqMappingClass.produces());
			}

			methodAttributes.calculateHeadersForClass(method.getDeclaringClass());
			methodAttributes.calculateConsumesProduces(method);

			Operation operation = (existingOperation != null) ? existingOperation : new Operation();

			if (isDeprecated(method))
				operation.setDeprecated(true);

			// Add documentation from operation annotation
			if (apiOperation == null || StringUtils.isBlank(apiOperation.operationId()))
				apiOperation = AnnotatedElementUtils.findMergedAnnotation(method,
						io.swagger.v3.oas.annotations.Operation.class);

			calculateJsonView(apiOperation, methodAttributes, method);
			if (apiOperation != null)
				openAPI = operationParser.parse(apiOperation, operation, openAPI, methodAttributes);
			fillParametersList(operation, queryParams, methodAttributes);

			// compute tags
			operation = openAPIService.buildTags(handlerMethod, operation, openAPI, locale);

			io.swagger.v3.oas.annotations.parameters.RequestBody requestBodyDoc = AnnotatedElementUtils.findMergedAnnotation(method,
					io.swagger.v3.oas.annotations.parameters.RequestBody.class);

			// RequestBody in Operation
			requestBuilder.getRequestBodyBuilder()
					.buildRequestBodyFromDoc(requestBodyDoc, methodAttributes, components,
							methodAttributes.getJsonViewAnnotationForRequestBody())
					.ifPresent(operation::setRequestBody);
			// requests
			operation = requestBuilder.build(handlerMethod, requestMethod, operation, methodAttributes, openAPI);

			// responses
			ApiResponses apiResponses = responseBuilder.build(components, handlerMethod, operation, methodAttributes);
			operation.setResponses(apiResponses);

			// get javadoc method description
			if (javadocProvider != null) {
				String description = javadocProvider.getMethodJavadocDescription(handlerMethod.getMethod());
				String summary = javadocProvider.getFirstSentence(description);
				boolean emptyOverrideDescription = StringUtils.isEmpty(operation.getDescription());
				boolean emptyOverrideSummary = StringUtils.isEmpty(operation.getSummary());
				if (!StringUtils.isEmpty(description) && emptyOverrideDescription) {
					operation.setDescription(description);
				}
				// if there is a previously set description
				// but no summary then it is intentional
				// we keep it as is
				if (!StringUtils.isEmpty(summary) && emptyOverrideSummary && emptyOverrideDescription) {
					operation.setSummary(javadocProvider.getFirstSentence(description));
				}
			}

			Set<io.swagger.v3.oas.annotations.callbacks.Callback> apiCallbacks = AnnotatedElementUtils.findMergedRepeatableAnnotations(method, io.swagger.v3.oas.annotations.callbacks.Callback.class);

			// callbacks
			buildCallbacks(openAPI, methodAttributes, operation, apiCallbacks);

			// allow for customisation
			operation = customizeOperation(operation, handlerMethod);

			PathItem pathItemObject = buildPathItem(requestMethod, operation, operationPath, paths);
			paths.addPathItem(operationPath, pathItemObject);
		}
	}

	/**
	 * Build callbacks.
	 *
	 * @param openAPI the open api
	 * @param methodAttributes the method attributes
	 * @param operation the operation
	 * @param apiCallbacks the api callbacks
	 */
	private void buildCallbacks(OpenAPI openAPI, MethodAttributes methodAttributes, Operation operation, Set<Callback> apiCallbacks) {
		if (!CollectionUtils.isEmpty(apiCallbacks))
			operationParser.buildCallbacks(apiCallbacks, openAPI, methodAttributes)
					.ifPresent(operation::setCallbacks);
	}

	/**
	 * Calculate path.
	 *
	 * @param routerOperationList the router operation list
	 * @param locale the locale
	 * @param openAPI the open api
	 */
	protected void calculatePath(List<RouterOperation> routerOperationList, Locale locale, OpenAPI openAPI) {
		ApplicationContext applicationContext = openAPIService.getContext();
		if (!CollectionUtils.isEmpty(routerOperationList)) {
			Collections.sort(routerOperationList);
			for (RouterOperation routerOperation : routerOperationList) {
				if (routerOperation.getBeanClass() != null && !Void.class.equals(routerOperation.getBeanClass())) {
					Object handlerBean = applicationContext.getBean(routerOperation.getBeanClass());
					HandlerMethod handlerMethod = null;

					if (StringUtils.isNotBlank(routerOperation.getBeanMethod())) {
						try {
							if (ArrayUtils.isEmpty(routerOperation.getParameterTypes())) {
								Method[] declaredMethods = AopUtils.getTargetClass(handlerBean).getDeclaredMethods();
								Optional<Method> methodOptional = Arrays.stream(declaredMethods)
										.filter(method -> routerOperation.getBeanMethod().equals(method.getName()) && method.getParameters().length == 0)
										.findAny();
								if (!methodOptional.isPresent())
									methodOptional = Arrays.stream(declaredMethods)
											.filter(method1 -> routerOperation.getBeanMethod().equals(method1.getName()))
											.findAny();
								if (methodOptional.isPresent())
									handlerMethod = new HandlerMethod(handlerBean, methodOptional.get());
							}
							else
								handlerMethod = new HandlerMethod(handlerBean, routerOperation.getBeanMethod(), routerOperation.getParameterTypes());
						}
						catch (NoSuchMethodException e) {
							LOGGER.error(e.getMessage());
						}
						if (handlerMethod != null && isFilterCondition(handlerMethod, routerOperation.getPath(), routerOperation.getProduces(), routerOperation.getConsumes(), routerOperation.getHeaders()))
							calculatePath(handlerMethod, routerOperation, locale, openAPI);
					}
				}
				else if (routerOperation.getOperation() != null && StringUtils.isNotBlank(routerOperation.getOperation().operationId()) && isFilterCondition(routerOperation.getPath(), routerOperation.getProduces(), routerOperation.getConsumes(), routerOperation.getHeaders())) {
					calculatePath(routerOperation, locale, openAPI);
				}
				else if (routerOperation.getOperationModel() != null && StringUtils.isNotBlank(routerOperation.getOperationModel().getOperationId()) && isFilterCondition(routerOperation.getPath(), routerOperation.getProduces(), routerOperation.getConsumes(), routerOperation.getHeaders())) {
					calculatePath(routerOperation, locale, openAPI);
				}
			}
		}
	}

	/**
	 * Calculate path.
	 *
	 * @param routerOperation the router operation
	 * @param locale the locale
	 */
	protected void calculatePath(RouterOperation routerOperation, Locale locale, OpenAPI openAPI) {
		routerOperation = customizeDataRestRouterOperation(routerOperation);

		String operationPath = routerOperation.getPath();
		io.swagger.v3.oas.annotations.Operation apiOperation = routerOperation.getOperation();
		String[] methodConsumes = routerOperation.getConsumes();
		String[] methodProduces = routerOperation.getProduces();
		String[] headers = routerOperation.getHeaders();
		Map<String, String> queryParams = routerOperation.getQueryParams();

		Paths paths = openAPI.getPaths();
		Map<HttpMethod, Operation> operationMap = null;
		if (paths.containsKey(operationPath)) {
			PathItem pathItem = paths.get(operationPath);
			operationMap = pathItem.readOperationsMap();
		}
		for (RequestMethod requestMethod : routerOperation.getMethods()) {
			Operation existingOperation = getExistingOperation(operationMap, requestMethod);
			MethodAttributes methodAttributes = new MethodAttributes(springDocConfigProperties.getDefaultConsumesMediaType(), springDocConfigProperties.getDefaultProducesMediaType(), methodConsumes, methodProduces, headers, locale);
			methodAttributes.setMethodOverloaded(existingOperation != null);
			Operation operation = getOperation(routerOperation, existingOperation);
			if (apiOperation != null)
				openAPI = operationParser.parse(apiOperation, operation, openAPI, methodAttributes);

			String operationId = operationParser.getOperationId(operation.getOperationId(), openAPI);
			operation.setOperationId(operationId);

			fillParametersList(operation, queryParams, methodAttributes);
			if (!CollectionUtils.isEmpty(operation.getParameters()))
				operation.getParameters().stream()
						.filter(parameter -> StringUtils.isEmpty(parameter.get$ref()))
						.forEach(parameter -> {
									if (parameter.getSchema() == null)
										parameter.setSchema(new StringSchema());
									if (parameter.getIn() == null)
										parameter.setIn(ParameterIn.QUERY.toString());
								}
						);
			PathItem pathItemObject = buildPathItem(requestMethod, operation, operationPath, paths);
			paths.addPathItem(operationPath, pathItemObject);
		}
	}

	/**
	 * Calculate path.
	 *
	 * @param handlerMethod the handler method
	 * @param operationPath the operation path
	 * @param requestMethods the request methods
	 * @param consumes the consumes
	 * @param produces the produces
	 * @param headers the headers
	 * @param params the params
	 * @param locale the locale
	 * @param openAPI the open api
	 */
	protected void calculatePath(HandlerMethod handlerMethod, String operationPath,
			Set<RequestMethod> requestMethods, String[] consumes, String[] produces, String[] headers, String[] params, Locale locale, OpenAPI openAPI) {
		this.calculatePath(handlerMethod,
				new RouterOperation(operationPath, requestMethods.toArray(new RequestMethod[requestMethods.size()]), consumes, produces, headers, params),
				locale, openAPI);
	}

	/**
	 * Gets router function paths.
	 *
	 * @param beanName the bean name
	 * @param routerFunctionVisitor the router function visitor
	 * @param locale the locale
	 * @param openAPI the open api
	 */
	protected void getRouterFunctionPaths(String beanName, AbstractRouterFunctionVisitor routerFunctionVisitor,
			Locale locale, OpenAPI openAPI) {
		boolean withRouterOperation = routerFunctionVisitor.getRouterFunctionDatas().stream()
				.anyMatch(routerFunctionData -> routerFunctionData.getAttributes().containsKey(OPERATION_ATTRIBUTE));
		if (withRouterOperation) {
			List<RouterOperation> operationList = routerFunctionVisitor.getRouterFunctionDatas().stream().map(RouterOperation::new).collect(Collectors.toList());
			calculatePath(operationList, locale, openAPI);
		}
		else {
			List<org.springdoc.core.annotations.RouterOperation> routerOperationList = new ArrayList<>();
			ApplicationContext applicationContext = openAPIService.getContext();
			RouterOperations routerOperations = applicationContext.findAnnotationOnBean(beanName, RouterOperations.class);
			if (routerOperations == null) {
				org.springdoc.core.annotations.RouterOperation routerOperation = applicationContext.findAnnotationOnBean(beanName, org.springdoc.core.annotations.RouterOperation.class);
				if (routerOperation != null)
					routerOperationList.add(routerOperation);
			}
			else
				routerOperationList.addAll(Arrays.asList(routerOperations.value()));
			if (routerOperationList.size() == 1)
				calculatePath(routerOperationList.stream().map(routerOperation -> new RouterOperation(routerOperation, routerFunctionVisitor.getRouterFunctionDatas().get(0))).collect(Collectors.toList()), locale, openAPI);
			else {
				List<RouterOperation> operationList = routerOperationList.stream().map(RouterOperation::new).collect(Collectors.toList());
				mergeRouters(routerFunctionVisitor.getRouterFunctionDatas(), operationList);
				calculatePath(operationList, locale, openAPI);
			}
		}
	}

	/**
	 * Is filter condition boolean.
	 *
	 * @param handlerMethod the handler method
	 * @param operationPath the operation path
	 * @param produces the produces
	 * @param consumes the consumes
	 * @param headers the headers
	 * @return the boolean
	 */
	protected boolean isFilterCondition(HandlerMethod handlerMethod, String operationPath, String[] produces, String[] consumes, String[] headers) {
		return isMethodToFilter(handlerMethod)
				&& isPackageToScan(handlerMethod.getBeanType().getPackage())
				&& isFilterCondition(operationPath, produces, consumes, headers);
	}

	/**
	 * Is target method suitable for inclusion in current documentation/
	 *
	 * @param handlerMethod the method to check
	 * @return whether the method should be included in the current OpenAPI definition
	 */
	protected boolean isMethodToFilter(HandlerMethod handlerMethod) {
		return this.springDocCustomizers.getMethodFilters()
				.map(Collection::stream)
				.map(stream -> stream.allMatch(m -> m.isMethodToInclude(handlerMethod.getMethod())))
				.orElse(true);
	}

	/**
	 * Is condition to match boolean.
	 *
	 * @param existingConditions the existing conditions
	 * @param conditionType the condition type
	 * @return the boolean
	 */
	protected boolean isConditionToMatch(String[] existingConditions, ConditionType conditionType) {
		List<String> conditionsToMatch = getConditionsToMatch(conditionType);
		if (CollectionUtils.isEmpty(conditionsToMatch)) {
			Optional<GroupConfig> optionalGroupConfig = springDocConfigProperties.getGroupConfigs().stream().filter(groupConfig -> this.groupName.equals(groupConfig.getGroup())).findAny();
			if (optionalGroupConfig.isPresent())
				conditionsToMatch = getConditionsToMatch(conditionType, optionalGroupConfig.get());
		}
		return CollectionUtils.isEmpty(conditionsToMatch)
				|| (!ArrayUtils.isEmpty(existingConditions) && conditionsToMatch.size() == existingConditions.length && conditionsToMatch.containsAll(Arrays.asList(existingConditions)));
	}

	/**
	 * Is package to scan boolean.
	 *
	 * @param aPackage the a package
	 * @return the boolean
	 */
	protected boolean isPackageToScan(Package aPackage) {
		if (aPackage == null)
			return true;
		final String packageName = aPackage.getName();
		List<String> packagesToScan = springDocConfigProperties.getPackagesToScan();
		List<String> packagesToExclude = springDocConfigProperties.getPackagesToExclude();
		if (CollectionUtils.isEmpty(packagesToScan)) {
			Optional<GroupConfig> optionalGroupConfig = springDocConfigProperties.getGroupConfigs().stream().filter(groupConfig -> this.groupName.equals(groupConfig.getGroup())).findAny();
			if (optionalGroupConfig.isPresent())
				packagesToScan = optionalGroupConfig.get().getPackagesToScan();
		}
		if (CollectionUtils.isEmpty(packagesToExclude)) {
			Optional<GroupConfig> optionalGroupConfig = springDocConfigProperties.getGroupConfigs().stream().filter(groupConfig -> this.groupName.equals(groupConfig.getGroup())).findAny();
			if (optionalGroupConfig.isPresent())
				packagesToExclude = optionalGroupConfig.get().getPackagesToExclude();
		}
		boolean include = CollectionUtils.isEmpty(packagesToScan)
				|| packagesToScan.stream().anyMatch(pack -> packageName.equals(pack)
				|| packageName.startsWith(pack + DOT));
		boolean exclude = !CollectionUtils.isEmpty(packagesToExclude)
				&& (packagesToExclude.stream().anyMatch(pack -> packageName.equals(pack)
				|| packageName.startsWith(pack + DOT)));

		return include && !exclude;
	}

	/**
	 * Is path to match boolean.
	 *
	 * @param operationPath the operation path
	 * @return the boolean
	 */
	protected boolean isPathToMatch(String operationPath) {
		List<String> pathsToMatch = springDocConfigProperties.getPathsToMatch();
		List<String> pathsToExclude = springDocConfigProperties.getPathsToExclude();
		if (CollectionUtils.isEmpty(pathsToMatch)) {
			Optional<GroupConfig> optionalGroupConfig = springDocConfigProperties.getGroupConfigs().stream().filter(groupConfig -> this.groupName.equals(groupConfig.getGroup())).findAny();
			if (optionalGroupConfig.isPresent())
				pathsToMatch = optionalGroupConfig.get().getPathsToMatch();
		}
		if (CollectionUtils.isEmpty(pathsToExclude)) {
			Optional<GroupConfig> optionalGroupConfig = springDocConfigProperties.getGroupConfigs().stream().filter(groupConfig -> this.groupName.equals(groupConfig.getGroup())).findAny();
			if (optionalGroupConfig.isPresent())
				pathsToExclude = optionalGroupConfig.get().getPathsToExclude();
		}
		boolean include = CollectionUtils.isEmpty(pathsToMatch) || pathsToMatch.stream().anyMatch(pattern -> antPathMatcher.match(pattern, operationPath));
		boolean exclude = !CollectionUtils.isEmpty(pathsToExclude) && pathsToExclude.stream().anyMatch(pattern -> antPathMatcher.match(pattern, operationPath));
		return include && !exclude;
	}

	/**
	 * Decode string.
	 *
	 * @param requestURI the request uri
	 * @return the string
	 */
	protected String decode(String requestURI) {
		try {
			return URLDecoder.decode(requestURI, StandardCharsets.UTF_8.toString());
		}
		catch (UnsupportedEncodingException e) {
			return requestURI;
		}
	}

	/**
	 * Is additional rest controller boolean.
	 *
	 * @param rawClass the raw class
	 * @return the boolean
	 */
	protected boolean isAdditionalRestController(Class<?> rawClass) {
		return ADDITIONAL_REST_CONTROLLERS.stream().anyMatch(clazz -> clazz.isAssignableFrom(rawClass));
	}

	/**
	 * Is rest controller boolean.
	 *
	 * @param restControllers the rest controllers
	 * @param handlerMethod the handler method
	 * @param operationPath the operation path
	 * @return the boolean
	 */
	protected boolean isRestController(Map<String, Object> restControllers, HandlerMethod handlerMethod,
			String operationPath) {
		boolean hasOperationAnnotation = AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), io.swagger.v3.oas.annotations.Operation.class);

		return ((containsResponseBody(handlerMethod) || hasOperationAnnotation) && restControllers.containsKey(handlerMethod.getBean().toString()) || isAdditionalRestController(handlerMethod.getBeanType()))
				&& operationPath.startsWith(DEFAULT_PATH_SEPARATOR)
				&& (springDocConfigProperties.isModelAndViewAllowed() || modelAndViewClass == null || !modelAndViewClass.isAssignableFrom(handlerMethod.getMethod().getReturnType()));
	}

	/**
	 * Gets default allowed http methods.
	 *
	 * @return the default allowed http methods
	 */
	protected Set<RequestMethod> getDefaultAllowedHttpMethods() {
		RequestMethod[] allowedRequestMethods = { RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.OPTIONS, RequestMethod.HEAD };
		return new HashSet<>(Arrays.asList(allowedRequestMethods));
	}

	/**
	 * Customise operation operation.
	 *
	 * @param operation the operation
	 * @param handlerMethod the handler method
	 * @return the operation
	 */
	protected Operation customizeOperation(Operation operation, HandlerMethod handlerMethod) {
		Optional<List<OperationCustomizer>> optionalOperationCustomizers = springDocCustomizers.getOperationCustomizers();
		if (optionalOperationCustomizers.isPresent()) {
			List<OperationCustomizer> operationCustomizerList = optionalOperationCustomizers.get();
			for (OperationCustomizer operationCustomizer : operationCustomizerList)
				operation = operationCustomizer.customize(operation, handlerMethod);
		}
		return operation;
	}

	/**
	 * Customise router operation
	 *
	 * @param routerOperation the router operation
	 * @param handlerMethod the handler method
	 * @return the router operation
	 */
	protected RouterOperation customizeRouterOperation(RouterOperation routerOperation, HandlerMethod handlerMethod) {
		Optional<List<RouterOperationCustomizer>> optionalRouterOperationCustomizers = springDocCustomizers.getRouterOperationCustomizers();
		if (optionalRouterOperationCustomizers.isPresent()) {
			List<RouterOperationCustomizer> routerOperationCustomizerList = optionalRouterOperationCustomizers.get();
			for (RouterOperationCustomizer routerOperationCustomizer : routerOperationCustomizerList) {
				routerOperation = routerOperationCustomizer.customize(routerOperation, handlerMethod);
			}
		}
		return routerOperation;
	}

	/**
	 * Merge routers.
	 *
	 * @param routerFunctionDatas the router function datas
	 * @param routerOperationList the router operation list
	 */
	protected void mergeRouters(List<RouterFunctionData> routerFunctionDatas, List<RouterOperation> routerOperationList) {
		for (RouterOperation routerOperation : routerOperationList) {
			if (StringUtils.isNotBlank(routerOperation.getPath())) {
				// PATH
				List<RouterFunctionData> routerFunctionDataList = routerFunctionDatas.stream()
						.filter(routerFunctionData1 -> routerFunctionData1.getPath().equals(routerOperation.getPath()))
						.collect(Collectors.toList());
				if (routerFunctionDataList.size() == 1)
					fillRouterOperation(routerFunctionDataList.get(0), routerOperation);
				else if (routerFunctionDataList.size() > 1 && ArrayUtils.isNotEmpty(routerOperation.getMethods())) {
					// PATH + METHOD
					routerFunctionDataList = routerFunctionDatas.stream()
							.filter(routerFunctionData1 -> routerFunctionData1.getPath().equals(routerOperation.getPath())
									&& isEqualMethods(routerOperation.getMethods(), routerFunctionData1.getMethods()))
							.collect(Collectors.toList());
					if (routerFunctionDataList.size() == 1)
						fillRouterOperation(routerFunctionDataList.get(0), routerOperation);
					else if (routerFunctionDataList.size() > 1 && ArrayUtils.isNotEmpty(routerOperation.getProduces())) {
						// PATH + METHOD + PRODUCES
						routerFunctionDataList = routerFunctionDatas.stream()
								.filter(routerFunctionData1 -> routerFunctionData1.getPath().equals(routerOperation.getPath())
										&& isEqualMethods(routerOperation.getMethods(), routerFunctionData1.getMethods())
										&& isEqualArrays(routerFunctionData1.getProduces(), routerOperation.getProduces()))
								.collect(Collectors.toList());
						if (routerFunctionDataList.size() == 1)
							fillRouterOperation(routerFunctionDataList.get(0), routerOperation);
						else if (routerFunctionDataList.size() > 1 && ArrayUtils.isNotEmpty(routerOperation.getConsumes())) {
							// PATH + METHOD + PRODUCES + CONSUMES
							routerFunctionDataList = routerFunctionDatas.stream()
									.filter(routerFunctionData1 -> routerFunctionData1.getPath().equals(routerOperation.getPath())
											&& isEqualMethods(routerOperation.getMethods(), routerFunctionData1.getMethods())
											&& isEqualArrays(routerFunctionData1.getProduces(), routerOperation.getProduces())
											&& isEqualArrays(routerFunctionData1.getConsumes(), routerOperation.getConsumes()))
									.collect(Collectors.toList());
							if (routerFunctionDataList.size() == 1)
								fillRouterOperation(routerFunctionDataList.get(0), routerOperation);
						}
					}
					else if (routerFunctionDataList.size() > 1 && ArrayUtils.isNotEmpty(routerOperation.getConsumes())) {
						// PATH + METHOD + CONSUMES
						routerFunctionDataList = routerFunctionDatas.stream()
								.filter(routerFunctionData1 -> routerFunctionData1.getPath().equals(routerOperation.getPath())
										&& isEqualMethods(routerOperation.getMethods(), routerFunctionData1.getMethods())
										&& isEqualArrays(routerFunctionData1.getConsumes(), routerOperation.getConsumes()))
								.collect(Collectors.toList());
						if (routerFunctionDataList.size() == 1)
							fillRouterOperation(routerFunctionDataList.get(0), routerOperation);
					}
				}
				else if (routerFunctionDataList.size() > 1 && ArrayUtils.isNotEmpty(routerOperation.getProduces())) {
					// PATH + PRODUCES
					routerFunctionDataList = routerFunctionDatas.stream()
							.filter(routerFunctionData1 -> routerFunctionData1.getPath().equals(routerOperation.getPath())
									&& isEqualArrays(routerFunctionData1.getProduces(), routerOperation.getProduces()))
							.collect(Collectors.toList());
					if (routerFunctionDataList.size() == 1)
						fillRouterOperation(routerFunctionDataList.get(0), routerOperation);
					else if (routerFunctionDataList.size() > 1 && ArrayUtils.isNotEmpty(routerOperation.getConsumes())) {
						// PATH + PRODUCES + CONSUMES
						routerFunctionDataList = routerFunctionDatas.stream()
								.filter(routerFunctionData1 -> routerFunctionData1.getPath().equals(routerOperation.getPath())
										&& isEqualMethods(routerOperation.getMethods(), routerFunctionData1.getMethods())
										&& isEqualArrays(routerFunctionData1.getConsumes(), routerOperation.getConsumes())
										&& isEqualArrays(routerFunctionData1.getProduces(), routerOperation.getProduces()))
								.collect(Collectors.toList());
						if (routerFunctionDataList.size() == 1)
							fillRouterOperation(routerFunctionDataList.get(0), routerOperation);
					}
				}
				else if (routerFunctionDataList.size() > 1 && ArrayUtils.isNotEmpty(routerOperation.getConsumes())) {
					// PATH + CONSUMES
					routerFunctionDataList = routerFunctionDatas.stream()
							.filter(routerFunctionData1 -> routerFunctionData1.getPath().equals(routerOperation.getPath())
									&& isEqualArrays(routerFunctionData1.getConsumes(), routerOperation.getConsumes()))
							.collect(Collectors.toList());
					if (routerFunctionDataList.size() == 1)
						fillRouterOperation(routerFunctionDataList.get(0), routerOperation);
				}
			}
		}
	}

	/**
	 * Calculate json view.
	 *
	 * @param apiOperation the api operation
	 * @param methodAttributes the method attributes
	 * @param method the method
	 */
	private void calculateJsonView(io.swagger.v3.oas.annotations.Operation apiOperation,
			MethodAttributes methodAttributes, Method method) {
		JsonView jsonViewAnnotation;
		JsonView jsonViewAnnotationForRequestBody;
		if (apiOperation != null && apiOperation.ignoreJsonView()) {
			jsonViewAnnotation = null;
			jsonViewAnnotationForRequestBody = null;
		}
		else {
			jsonViewAnnotation = AnnotatedElementUtils.findMergedAnnotation(method, JsonView.class);
			/*
			 * If one and only one exists, use the @JsonView annotation from the method
			 * parameter annotated with @RequestBody. Otherwise fall back to the @JsonView
			 * annotation for the method itself.
			 */
			jsonViewAnnotationForRequestBody = (JsonView) Arrays.stream(ReflectionUtils.getParameterAnnotations(method))
					.filter(arr -> Arrays.stream(arr)
							.anyMatch(annotation -> (annotation.annotationType()
									.equals(io.swagger.v3.oas.annotations.parameters.RequestBody.class) || annotation.annotationType().equals(RequestBody.class))))
					.flatMap(Arrays::stream).filter(annotation -> annotation.annotationType().equals(JsonView.class))
					.reduce((a, b) -> null).orElse(jsonViewAnnotation);
		}
		methodAttributes.setJsonViewAnnotation(jsonViewAnnotation);
		methodAttributes.setJsonViewAnnotationForRequestBody(jsonViewAnnotationForRequestBody);
	}

	/**
	 * Is equal arrays boolean.
	 *
	 * @param array1 the array 1
	 * @param array2 the array 2
	 * @return the boolean
	 */
	private boolean isEqualArrays(String[] array1, String[] array2) {
		Arrays.sort(array1);
		Arrays.sort(array2);
		return Arrays.equals(array1, array2);
	}

	/**
	 * Is equal methods boolean.
	 *
	 * @param requestMethods1 the request methods 1
	 * @param requestMethods2 the request methods 2
	 * @return the boolean
	 */
	private boolean isEqualMethods(RequestMethod[] requestMethods1, RequestMethod[] requestMethods2) {
		Arrays.sort(requestMethods1);
		Arrays.sort(requestMethods2);
		return Arrays.equals(requestMethods1, requestMethods2);
	}

	/**
	 * Fill parameters list.
	 *
	 * @param operation the operation
	 * @param queryParams the query params
	 * @param methodAttributes the method attributes
	 */
	private void fillParametersList(Operation operation, Map<String, String> queryParams, MethodAttributes methodAttributes) {
		List<Parameter> parametersList = operation.getParameters();
		if (parametersList == null)
			parametersList = new ArrayList<>();
		Collection<Parameter> headersMap = AbstractRequestService.getHeaders(methodAttributes, new LinkedHashMap<>());
		headersMap.forEach(parameter -> {
			Optional<Parameter> existingParam;
			if (!CollectionUtils.isEmpty(operation.getParameters())){
				existingParam = operation.getParameters().stream().filter(p -> parameter.getName().equals(p.getName())).findAny();
				if (!existingParam.isPresent())
					operation.addParametersItem(parameter);
			}
		});

		if (!CollectionUtils.isEmpty(queryParams)) {
			for (Map.Entry<String, String> entry : queryParams.entrySet()) {
				io.swagger.v3.oas.models.parameters.Parameter parameter = new io.swagger.v3.oas.models.parameters.Parameter();
				parameter.setName(entry.getKey());
				parameter.setSchema(new StringSchema()._default(entry.getValue()));
				parameter.setRequired(true);
				parameter.setIn(ParameterIn.QUERY.toString());
				GenericParameterService.mergeParameter(parametersList, parameter);
			}
			operation.setParameters(parametersList);
		}
	}

	/**
	 * Fill router operation.
	 *
	 * @param routerFunctionData the router function data
	 * @param routerOperation the router operation
	 */
	private void fillRouterOperation(RouterFunctionData routerFunctionData, RouterOperation routerOperation) {
		if (ArrayUtils.isEmpty(routerOperation.getConsumes()))
			routerOperation.setConsumes(routerFunctionData.getConsumes());
		if (ArrayUtils.isEmpty(routerOperation.getProduces()))
			routerOperation.setProduces(routerFunctionData.getProduces());
		if (ArrayUtils.isEmpty(routerOperation.getHeaders()))
			routerOperation.setHeaders(routerFunctionData.getHeaders());
		if (ArrayUtils.isEmpty(routerOperation.getMethods()))
			routerOperation.setMethods(routerFunctionData.getMethods());
		if (CollectionUtils.isEmpty(routerOperation.getQueryParams()))
			routerOperation.setQueryParams(routerFunctionData.getQueryParams());
	}

	/**
	 * Build path item path item.
	 *
	 * @param requestMethod the request method
	 * @param operation the operation
	 * @param operationPath the operation path
	 * @param paths the paths
	 * @return the path item
	 */
	private PathItem buildPathItem(RequestMethod requestMethod, Operation operation, String operationPath,
			Paths paths) {
		PathItem pathItemObject;
		if(operation!=null && !CollectionUtils.isEmpty(operation.getParameters())){
			Iterator<Parameter> paramIt = operation.getParameters().iterator();
			while (paramIt.hasNext()){
				Parameter parameter = paramIt.next();
				if(ParameterIn.PATH.toString().equals(parameter.getIn())){
					// check it's present in the path
					if(!StringUtils.containsAny(operationPath, "{" + parameter.getName() + "}", "{*" + parameter.getName() + "}"))
						paramIt.remove();
				}
			}
		}
		if (paths.containsKey(operationPath))
			pathItemObject = paths.get(operationPath);
		else
			pathItemObject = new PathItem();

		switch (requestMethod) {
			case POST:
				pathItemObject.post(operation);
				break;
			case GET:
				pathItemObject.get(operation);
				break;
			case DELETE:
				pathItemObject.delete(operation);
				break;
			case PUT:
				pathItemObject.put(operation);
				break;
			case PATCH:
				pathItemObject.patch(operation);
				break;
			case TRACE:
				pathItemObject.trace(operation);
				break;
			case HEAD:
				pathItemObject.head(operation);
				break;
			case OPTIONS:
				pathItemObject.options(operation);
				break;
			default:
				// Do nothing here
				break;
		}
		return pathItemObject;
	}

	/**
	 * Gets existing operation.
	 *
	 * @param operationMap the operation map
	 * @param requestMethod the request method
	 * @return the existing operation
	 */
	private Operation getExistingOperation(Map<HttpMethod, Operation> operationMap, RequestMethod requestMethod) {
		Operation existingOperation = null;
		if (!CollectionUtils.isEmpty(operationMap)) {
			// Get existing operation definition
			switch (requestMethod) {
				case GET:
					existingOperation = operationMap.get(HttpMethod.GET);
					break;
				case POST:
					existingOperation = operationMap.get(HttpMethod.POST);
					break;
				case PUT:
					existingOperation = operationMap.get(HttpMethod.PUT);
					break;
				case DELETE:
					existingOperation = operationMap.get(HttpMethod.DELETE);
					break;
				case PATCH:
					existingOperation = operationMap.get(HttpMethod.PATCH);
					break;
				case HEAD:
					existingOperation = operationMap.get(HttpMethod.HEAD);
					break;
				case OPTIONS:
					existingOperation = operationMap.get(HttpMethod.OPTIONS);
					break;
				default:
					// Do nothing here
					break;
			}
		}
		return existingOperation;
	}

	/**
	 * Gets operation.
	 *
	 * @param routerOperation the router operation
	 * @param existingOperation the existing operation
	 * @return the operation
	 */
	private Operation getOperation(RouterOperation routerOperation, Operation existingOperation) {
		Operation operationModel = routerOperation.getOperationModel();
		Operation operation;
		if (existingOperation != null && operationModel == null) {
			operation = existingOperation;
		}
		else if (existingOperation == null && operationModel != null) {
			operation = operationModel;
		}
		else if (existingOperation != null) {
			operation = operationParser.mergeOperation(existingOperation, operationModel);
		}
		else {
			operation = new Operation();
		}
		return operation;
	}

	/**
	 * Init open api builder.
	 * @param locale the locale
	 */
	protected void initOpenAPIBuilder(Locale locale) {
		locale = locale == null ? Locale.getDefault() : locale;
		if (openAPIService.getCachedOpenAPI(locale) != null && springDocConfigProperties.isCacheDisabled()) {
			openAPIService = openAPIBuilderObjectFactory.getObject();
		}
	}

	/**
	 * Write yaml value string.
	 *
	 * @param openAPI the open api
	 * @return the string
	 * @throws JsonProcessingException the json processing exception
	 */
	protected byte[] writeYamlValue(OpenAPI openAPI) throws JsonProcessingException {
		String result;
		ObjectMapper objectMapper = springDocProviders.yamlMapper();
		if (springDocConfigProperties.isWriterWithOrderByKeys())
			ObjectMapperProvider.sortOutput(objectMapper, springDocConfigProperties);
		YAMLFactory factory = (YAMLFactory) objectMapper.getFactory();
		factory.configure(Feature.USE_NATIVE_TYPE_ID, false);
		if (!springDocConfigProperties.isWriterWithDefaultPrettyPrinter())
			result = objectMapper.writerFor(OpenAPI.class).writeValueAsString(openAPI);
		else
			result = objectMapper.writerWithDefaultPrettyPrinter().forType(OpenAPI.class).writeValueAsString(openAPI);
		return result.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Gets actuator uri.
	 *
	 * @param scheme the scheme
	 * @param host the host
	 * @return the actuator uri
	 */
	protected URI getActuatorURI(String scheme, String host) {
		final Optional<ActuatorProvider> actuatorProviderOptional = springDocProviders.getActuatorProvider();
		URI uri = null;
		if (actuatorProviderOptional.isPresent()) {
			ActuatorProvider actuatorProvider = actuatorProviderOptional.get();
			int port;
			String path;
			if (ACTUATOR_DEFAULT_GROUP.equals(this.groupName)) {
				port = actuatorProvider.getActuatorPort();
				path = actuatorProvider.getActuatorPath();
			}
			else {
				port = actuatorProvider.getApplicationPort();
				path = actuatorProvider.getContextPath();
				String mvcServletPath = this.openAPIService.getContext().getBean(Environment.class).getProperty(SPRING_MVC_SERVLET_PATH);
				if (StringUtils.isNotEmpty(mvcServletPath))
					path = path + mvcServletPath;
			}
			try {
				uri = new URI(StringUtils.defaultIfEmpty(scheme, "http"), null, StringUtils.defaultIfEmpty(host, "localhost"), port, path, null, null);
			}
			catch (URISyntaxException e) {
				LOGGER.error("Unable to parse the URL: scheme {}, host {}, port {}, path {}", scheme, host, port, path);
			}
		}
		return uri;
	}

	/**
	 * Is actuator rest controller boolean.
	 *
	 * @param operationPath the operation path
	 * @param handlerMethod the handler method
	 * @return the boolean
	 */
	protected boolean isActuatorRestController(String operationPath, HandlerMethod handlerMethod) {
		Optional<ActuatorProvider> actuatorProviderOptional = springDocProviders.getActuatorProvider();
		boolean isActuatorRestController = false;
		if (actuatorProviderOptional.isPresent())
			isActuatorRestController = actuatorProviderOptional.get().isRestController(operationPath, handlerMethod);
		return springDocConfigProperties.isShowActuator() && isActuatorRestController && (modelAndViewClass == null || !modelAndViewClass.isAssignableFrom(handlerMethod.getMethod().getReturnType()));
	}

	/**
	 * Write json value string.
	 *
	 * @param openAPI the open api
	 * @return the string
	 * @throws JsonProcessingException the json processing exception
	 */
	protected byte[] writeJsonValue(OpenAPI openAPI) throws JsonProcessingException {
		String result;
		ObjectMapper objectMapper = springDocProviders.jsonMapper();
		if (springDocConfigProperties.isWriterWithOrderByKeys())
			ObjectMapperProvider.sortOutput(objectMapper, springDocConfigProperties);
		if (!springDocConfigProperties.isWriterWithDefaultPrettyPrinter())
			result = objectMapper.writerFor(OpenAPI.class).writeValueAsString(openAPI);
		else
			result = objectMapper.writerWithDefaultPrettyPrinter().forType(OpenAPI.class).writeValueAsString(openAPI);
		return result.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Gets conditions to match.
	 *
	 * @param conditionType the condition type
	 * @param groupConfigs the group configs
	 * @return the conditions to match
	 */
	private List<String> getConditionsToMatch(ConditionType conditionType, GroupConfig... groupConfigs) {
		List<String> conditionsToMatch = null;
		GroupConfig groupConfig = null;
		if (ArrayUtils.isNotEmpty(groupConfigs))
			groupConfig = groupConfigs[0];
		switch (conditionType) {
			case HEADERS:
				conditionsToMatch = (groupConfig != null) ? groupConfig.getHeadersToMatch() : springDocConfigProperties.getHeadersToMatch();
				break;
			case PRODUCES:
				conditionsToMatch = (groupConfig != null) ? groupConfig.getProducesToMatch() : springDocConfigProperties.getProducesToMatch();
				break;
			case CONSUMES:
				conditionsToMatch = (groupConfig != null) ? groupConfig.getConsumesToMatch() : springDocConfigProperties.getConsumesToMatch();
				break;
			default:
				break;
		}
		return conditionsToMatch;
	}

	/**
	 * Is filter condition boolean.
	 *
	 * @param operationPath the operation path
	 * @param produces the produces
	 * @param consumes the consumes
	 * @param headers the headers
	 * @return the boolean
	 */
	private boolean isFilterCondition(String operationPath, String[] produces, String[] consumes, String[] headers) {
		return isPathToMatch(operationPath)
				&& isConditionToMatch(produces, ConditionType.PRODUCES)
				&& isConditionToMatch(consumes, ConditionType.CONSUMES)
				&& isConditionToMatch(headers, ConditionType.HEADERS);
	}

	/**
	 * The enum Condition type.
	 */
	enum ConditionType {
		/**
		 *Produces condition type.
		 */
		PRODUCES,
		/**
		 *Consumes condition type.
		 */
		CONSUMES,
		/**
		 *Headers condition type.
		 */
		HEADERS
	}

	/**
	 * Customize data rest router operation router operation.
	 *
	 * @param routerOperation the router operation
	 * @return the router operation
	 */
	private RouterOperation customizeDataRestRouterOperation(RouterOperation routerOperation) {
		Optional<List<DataRestRouterOperationCustomizer>> optionalDataRestRouterOperationCustomizers = springDocCustomizers.getDataRestRouterOperationCustomizers();
		if (optionalDataRestRouterOperationCustomizers.isPresent()) {
			List<DataRestRouterOperationCustomizer> dataRestRouterOperationCustomizerList = optionalDataRestRouterOperationCustomizers.get();
			for (DataRestRouterOperationCustomizer dataRestRouterOperationCustomizer : dataRestRouterOperationCustomizerList) {
				routerOperation = dataRestRouterOperationCustomizer.customize(routerOperation);
			}
		}
		return routerOperation;
	}
}