/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.extension.internal.loader;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.Boolean.parseBoolean;
import static java.lang.String.format;
import static java.lang.String.join;
import static java.lang.Thread.currentThread;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static javax.xml.XMLConstants.XMLNS_ATTRIBUTE;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.mule.metadata.api.model.MetadataFormat.JAVA;
import static org.mule.metadata.catalog.api.PrimitiveTypesTypeLoader.PRIMITIVE_TYPES;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.api.meta.model.display.LayoutModel.builder;
import static org.mule.runtime.api.meta.model.parameter.ParameterRole.BEHAVIOUR;
import static org.mule.runtime.config.internal.dsl.model.extension.xml.MacroExpansionModuleModel.MODULE_CONNECTION_GLOBAL_ELEMENT_NAME;
import static org.mule.runtime.config.internal.dsl.model.extension.xml.MacroExpansionModuleModel.TNS_PREFIX;
import static org.mule.runtime.config.internal.dsl.model.extension.xml.MacroExpansionModulesModel.getUsedNamespaces;
import static org.mule.runtime.config.internal.model.ApplicationModel.GLOBAL_PROPERTY;
import static org.mule.runtime.config.internal.model.type.ApplicationModelTypeUtils.resolveTypedComponentIdentifier;
import static org.mule.runtime.core.api.exception.Errors.ComponentIdentifiers.Handleable.ANY;
import static org.mule.runtime.dsl.api.xml.parser.XmlConfigurationDocumentLoader.schemaValidatingDocumentLoader;
import static org.mule.runtime.extension.api.util.XmlModelUtils.createXmlLanguageModel;

import org.mule.metadata.api.builder.BaseTypeBuilder;
import org.mule.metadata.api.model.MetadataType;
import org.mule.metadata.catalog.api.TypeResolver;
import org.mule.metadata.catalog.api.TypeResolverException;
import org.mule.runtime.api.component.ComponentIdentifier;
import org.mule.runtime.api.connection.ConnectionProvider;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.meta.Category;
import org.mule.runtime.api.meta.model.ExtensionModel;
import org.mule.runtime.api.meta.model.XmlDslModel;
import org.mule.runtime.api.meta.model.config.ConfigurationModel;
import org.mule.runtime.api.meta.model.connection.ConnectionManagementType;
import org.mule.runtime.api.meta.model.connection.ConnectionProviderModel;
import org.mule.runtime.api.meta.model.declaration.fluent.ConfigurationDeclarer;
import org.mule.runtime.api.meta.model.declaration.fluent.ConnectionProviderDeclarer;
import org.mule.runtime.api.meta.model.declaration.fluent.ExtensionDeclarer;
import org.mule.runtime.api.meta.model.declaration.fluent.HasOperationDeclarer;
import org.mule.runtime.api.meta.model.declaration.fluent.OperationDeclarer;
import org.mule.runtime.api.meta.model.declaration.fluent.OutputDeclarer;
import org.mule.runtime.api.meta.model.declaration.fluent.ParameterDeclarer;
import org.mule.runtime.api.meta.model.declaration.fluent.ParameterizedDeclarer;
import org.mule.runtime.api.meta.model.display.DisplayModel;
import org.mule.runtime.api.meta.model.display.LayoutModel;
import org.mule.runtime.api.meta.model.error.ErrorModelBuilder;
import org.mule.runtime.api.meta.model.parameter.ParameterRole;
import org.mule.runtime.api.util.Pair;
import org.mule.runtime.ast.api.ComponentAst;
import org.mule.runtime.config.api.dsl.model.properties.ConfigurationPropertiesProvider;
import org.mule.runtime.config.api.dsl.model.properties.ConfigurationProperty;
import org.mule.runtime.config.internal.ModuleDelegatingEntityResolver;
import org.mule.runtime.config.internal.dsl.model.ClassLoaderResourceProvider;
import org.mule.runtime.config.internal.dsl.model.ComponentLocationVisitor;
import org.mule.runtime.config.internal.dsl.model.ComponentModelReader;
import org.mule.runtime.config.internal.dsl.model.ExtensionModelHelper;
import org.mule.runtime.config.internal.dsl.model.config.ConfigurationPropertiesResolver;
import org.mule.runtime.config.internal.dsl.model.config.DefaultConfigurationPropertiesResolver;
import org.mule.runtime.config.internal.dsl.model.config.DefaultConfigurationProperty;
import org.mule.runtime.config.internal.dsl.model.config.EnvironmentPropertiesConfigurationProvider;
import org.mule.runtime.config.internal.dsl.model.config.FileConfigurationPropertiesProvider;
import org.mule.runtime.config.internal.dsl.model.config.GlobalPropertyConfigurationPropertiesProvider;
import org.mule.runtime.config.internal.dsl.model.config.PropertyNotFoundException;
import org.mule.runtime.config.internal.dsl.model.extension.xml.MacroExpansionModuleModel;
import org.mule.runtime.config.internal.dsl.model.extension.xml.MacroExpansionModulesModel;
import org.mule.runtime.config.internal.dsl.model.extension.xml.property.GlobalElementComponentModelModelProperty;
import org.mule.runtime.config.internal.dsl.model.extension.xml.property.OperationComponentModelModelProperty;
import org.mule.runtime.config.internal.dsl.model.extension.xml.property.PrivateOperationsModelProperty;
import org.mule.runtime.config.internal.dsl.model.extension.xml.property.TestConnectionGlobalElementModelProperty;
import org.mule.runtime.config.internal.dsl.xml.XmlNamespaceInfoProviderSupplier;
import org.mule.runtime.config.internal.model.ComponentModel;
import org.mule.runtime.config.internal.util.NoOpXmlErrorHandler;
import org.mule.runtime.core.api.util.xmlsecurity.XMLSecureFactories;
import org.mule.runtime.dsl.api.xml.parser.ConfigLine;
import org.mule.runtime.dsl.api.xml.parser.XmlConfigurationDocumentLoader;
import org.mule.runtime.dsl.internal.xml.parser.XmlApplicationParser;
import org.mule.runtime.extension.api.annotation.param.display.Placement;
import org.mule.runtime.extension.api.dsl.syntax.resolver.DslSyntaxResolver;
import org.mule.runtime.extension.api.exception.IllegalModelDefinitionException;
import org.mule.runtime.extension.api.exception.IllegalParameterModelDefinitionException;
import org.mule.runtime.extension.api.loader.ExtensionLoadingContext;
import org.mule.runtime.extension.api.loader.xml.declaration.DeclarationOperation;
import org.mule.runtime.extension.api.property.XmlExtensionModelProperty;
import org.mule.runtime.extension.internal.loader.validator.ForbiddenConfigurationPropertiesValidator;
import org.mule.runtime.extension.internal.loader.validator.property.InvalidTestConnectionMarkerModelProperty;
import org.mule.runtime.extension.internal.property.NoReconnectionStrategyModelProperty;
import org.mule.runtime.internal.dsl.NullDslResolvingContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.jgrapht.Graph;
import org.jgrapht.alg.cycle.CycleDetector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.w3c.dom.Document;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;

/**
 * Describes an {@link ExtensionModel} by scanning an XML provided in the constructor
 *
 * @since 4.0
 */
public final class XmlExtensionLoaderDelegate {

  public static final String CYCLIC_OPERATIONS_ERROR = "Cyclic operations detected, offending ones: [%s]";

  private static final String PARAMETER_NAME = "name";
  private static final String PARAMETER_DEFAULT_VALUE = "defaultValue";
  private static final String TYPE_ATTRIBUTE = "type";
  private static final String MODULE_NAME = "name";
  private static final String MODULE_PREFIX_ATTRIBUTE = "prefix";
  private static final String MODULE_NAMESPACE_ATTRIBUTE = "namespace";
  private static final String MODULE_NAMESPACE_NAME = "module";
  protected static final String CONFIG_NAME = "config";

  private static final Map<String, ParameterRole> parameterRoleTypes = ImmutableMap.<String, ParameterRole>builder()
      .put("BEHAVIOUR", ParameterRole.BEHAVIOUR)
      .put("CONTENT", ParameterRole.CONTENT)
      .put("PRIMARY", ParameterRole.PRIMARY_CONTENT)
      .build();

  private static final String CATEGORY = "category";
  private static final String VENDOR = "vendor";
  private static final String DOC_DESCRIPTION = "doc:description";
  private static final String PASSWORD = "password";
  private static final String ORDER_ATTRIBUTE = "order";
  private static final String TAB_ATTRIBUTE = "tab";
  private static final String DISPLAY_NAME_ATTRIBUTE = "displayName";
  private static final String SUMMARY_ATTRIBUTE = "summary";
  private static final String EXAMPLE_ATTRIBUTE = "example";
  private static final String ERROR_TYPE_ATTRIBUTE = "type";
  private static final String ROLE = "role";
  private static final String ATTRIBUTE_USE = "use";
  private static final String ATTRIBUTE_VISIBILITY = "visibility";
  private static final String NAMESPACE_SEPARATOR = ":";

  private static final String TRANSFORMATION_FOR_TNS_RESOURCE = "META-INF/transform_for_tns.xsl";
  private static final String XMLNS_TNS = XMLNS_ATTRIBUTE + ":" + TNS_PREFIX;
  public static final String MODULE_CONNECTION_MARKER_ATTRIBUTE = "xmlns:connection";
  private static final String GLOBAL_ELEMENT_NAME_ATTRIBUTE = "name";

  /**
   * ENUM used to discriminate which type of {@link ParameterDeclarer} has to be created (required or not).
   *
   * @see #getParameterDeclarer(ParameterizedDeclarer, Map)
   */
  private enum UseEnum {
    REQUIRED, OPTIONAL, AUTO
  }

  /**
   * ENUM used to discriminate which visibility an <operation/> has.
   *
   * @see {@link XmlExtensionLoaderDelegate#loadOperationsFrom(HasOperationDeclarer, ComponentModel, DirectedGraph, XmlDslModel, XmlExtensionLoaderDelegate.OperationVisibility)}
   */
  private enum OperationVisibility {
    PRIVATE, PUBLIC
  }

  private static ParameterRole getRole(final String role) {
    if (!parameterRoleTypes.containsKey(role)) {
      throw new IllegalParameterModelDefinitionException(format("The parametrized role [%s] doesn't match any of the expected types [%s]",
                                                                role, join(", ", parameterRoleTypes.keySet())));
    }
    return parameterRoleTypes.get(role);
  }

  private static final ComponentIdentifier OPERATION_IDENTIFIER =
      ComponentIdentifier.builder().namespace(MODULE_NAMESPACE_NAME).name("operation").build();
  private static final ComponentIdentifier OPERATION_PROPERTY_IDENTIFIER =
      ComponentIdentifier.builder().namespace(MODULE_NAMESPACE_NAME).name("property").build();
  private static final ComponentIdentifier CONNECTION_PROPERTIES_IDENTIFIER =
      ComponentIdentifier.builder().namespace(MODULE_NAMESPACE_NAME).name("connection").build();
  private static final ComponentIdentifier OPERATION_PARAMETERS_IDENTIFIER =
      ComponentIdentifier.builder().namespace(MODULE_NAMESPACE_NAME).name("parameters").build();
  private static final ComponentIdentifier OPERATION_PARAMETER_IDENTIFIER =
      ComponentIdentifier.builder().namespace(MODULE_NAMESPACE_NAME).name("parameter").build();
  private static final ComponentIdentifier OPERATION_BODY_IDENTIFIER =
      ComponentIdentifier.builder().namespace(MODULE_NAMESPACE_NAME).name("body").build();
  private static final ComponentIdentifier OPERATION_OUTPUT_IDENTIFIER =
      ComponentIdentifier.builder().namespace(MODULE_NAMESPACE_NAME).name("output").build();
  private static final ComponentIdentifier OPERATION_OUTPUT_ATTRIBUTES_IDENTIFIER =
      ComponentIdentifier.builder().namespace(MODULE_NAMESPACE_NAME).name("output-attributes").build();
  private static final ComponentIdentifier OPERATION_ERRORS_IDENTIFIER =
      ComponentIdentifier.builder().namespace(MODULE_NAMESPACE_NAME).name("errors").build();
  private static final ComponentIdentifier OPERATION_ERROR_IDENTIFIER =
      ComponentIdentifier.builder().namespace(MODULE_NAMESPACE_NAME).name("error").build();
  private static final ComponentIdentifier MODULE_IDENTIFIER =
      ComponentIdentifier.builder().namespace(MODULE_NAMESPACE_NAME).name(MODULE_NAMESPACE_NAME)
          .build();
  public static final String XSD_SUFFIX = ".xsd";
  private static final String XML_SUFFIX = ".xml";
  private static final String TYPES_XML_SUFFIX = "-catalog" + XML_SUFFIX;

  private final String modulePath;
  private final boolean validateXml;
  private final Optional<String> declarationPath;
  private final List<String> resourcesPaths;
  private TypeResolver typeResolver;
  private Map<String, DeclarationOperation> declarationMap;

  /**
   * @param modulePath      relative path to a file that will be loaded from the current {@link ClassLoader}. Non null.
   * @param validateXml     true if the XML of the Smart Connector must ve valid, false otherwise. It will be false at runtime, as the
   *                        packaging of a connector will previously validate it's XML.
   * @param declarationPath relative path to a file that contains the {@link MetadataType}s of all <operations/>.
   * @param resourcesPaths  set of resources that will be exported in the {@link ExtensionModel}
   */
  public XmlExtensionLoaderDelegate(String modulePath, boolean validateXml, Optional<String> declarationPath,
                                    List<String> resourcesPaths) {
    checkArgument(!isEmpty(modulePath), "modulePath must not be empty");
    this.modulePath = modulePath;
    this.validateXml = validateXml;
    this.declarationPath = declarationPath;
    this.resourcesPaths = resourcesPaths;
  }

  public void declare(ExtensionLoadingContext context) {
    // We will assume the context classLoader of the current thread will be the one defined for the plugin (which is not filtered
    // and will allow us to access any resource in it
    URL resource = getResource(modulePath);
    if (resource == null) {
      throw new IllegalArgumentException(format("There's no reachable XML in the path '%s'", modulePath));
    }
    try {
      loadCustomTypes();
    } catch (Exception e) {
      throw new IllegalArgumentException(format("The custom type file [%s] for the module '%s' cannot be read properly",
                                                getCustomTypeFilename(), modulePath),
                                         e);
    }
    loadDeclaration();
    final ExtensionModelHelper extensionModelHelper =
        new ExtensionModelHelper(context.getDslResolvingContext().getExtensions(), context.getDslResolvingContext());

    final Set<ExtensionModel> extensions = new HashSet<>(extensionModelHelper.getExtensionsModels());
    try {
      createTnsExtensionModel(resource, extensionModelHelper).ifPresent(extensions::add);
    } catch (IOException e) {
      throw new MuleRuntimeException(createStaticMessage(format("There was an issue reading the stream for the resource %s",
                                                                resource.getFile())));
    }

    Document moduleDocument = getModuleDocument(extensions, resource);
    loadModuleExtension(context.getExtensionDeclarer(), resource, moduleDocument,
                        new ExtensionModelHelper(extensions), false);
  }

  private URL getResource(String resource) {
    return currentThread().getContextClassLoader().getResource(resource);
  }

  /**
   * Custom types might not exist for the current module, that's why it's handled with {@link #getEmptyTypeResolver()}.
   */
  private void loadCustomTypes() {
    final String customTypes = getCustomTypeFilename();
    final URL resourceCustomType = getResource(customTypes);
    if (resourceCustomType != null) {
      typeResolver = TypeResolver.createFrom(resourceCustomType, currentThread().getContextClassLoader());
    } else {
      typeResolver = getEmptyTypeResolver();
    }
  }

  private TypeResolver getEmptyTypeResolver() {
    return TypeResolver.create(currentThread().getContextClassLoader());
  }

  /**
   * Possible file with the custom types, works by convention.
   *
   * @return given a {@code modulePath} such as "module-custom-types.xml" returns "module-custom-types-types.xml". Not null
   */
  private String getCustomTypeFilename() {
    return modulePath.replace(XML_SUFFIX, TYPES_XML_SUFFIX);
  }

  /**
   * If a declaration file does exists, then it reads into a map to use it later on when describing the {@link ExtensionModel} of
   * the current <module/> for ever <operation/>s output and output attributes.
   */
  private void loadDeclaration() {
    declarationMap = new HashMap<>();
    declarationPath.ifPresent(operationsOutputPathValue -> {
      final URL operationsOutputResource = getResource(operationsOutputPathValue);
      if (operationsOutputResource != null) {
        try {
          declarationMap = DeclarationOperation.fromString(IOUtils.toString(operationsOutputResource));
        } catch (IOException e) {
          throw new IllegalArgumentException(format("The declarations file [%s] for the module '%s' cannot be read properly",
                                                    operationsOutputPathValue, modulePath),
                                             e);
        }
      }
    });
  }

  private Document getModuleDocument(Set<ExtensionModel> extensions, URL resource) {
    XmlConfigurationDocumentLoader xmlConfigurationDocumentLoader =
        validateXml
            ? schemaValidatingDocumentLoader()
            : schemaValidatingDocumentLoader(NoOpXmlErrorHandler::new);
    try {
      return xmlConfigurationDocumentLoader.loadDocument(() -> XMLSecureFactories.createDefault().getSAXParserFactory(),
                                                         new ModuleDelegatingEntityResolver(extensions), resource.getFile(),
                                                         resource.openStream());
    } catch (IOException e) {
      throw new MuleRuntimeException(createStaticMessage(format("There was an issue reading the stream for the resource %s",
                                                                resource.getFile())));
    }
  }

  /**
   * Transforms the current <module/> by stripping out the <body/>'s content, so that there are not parsing errors, to generate a
   * simpler {@link ExtensionModel} if there are references to the TNS prefix defined by the {@link #XMLNS_TNS}.
   *
   * @param resource             <module/>'s resource
   * @param extensionModelHelper with the complete list of extensions the current module depends on
   * @return an {@link ExtensionModel} if there's a {@link #XMLNS_TNS} defined, {@link Optional#empty()} otherwise
   * @throws IOException if it fails reading the resource
   */
  private Optional<ExtensionModel> createTnsExtensionModel(URL resource, ExtensionModelHelper extensionModelHelper)
      throws IOException {
    ExtensionModel result = null;
    final ByteArrayOutputStream resultStream = new ByteArrayOutputStream();
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(TRANSFORMATION_FOR_TNS_RESOURCE)) {
      final Source xslt = new StreamSource(in);
      final Source moduleToTransform = new StreamSource(resource.openStream());
      TransformerFactory.newInstance()
          .newTransformer(xslt)
          .transform(moduleToTransform, new StreamResult(resultStream));
    } catch (TransformerException e) {
      throw new MuleRuntimeException(
                                     createStaticMessage(format("There was an issue transforming the stream for the resource %s while trying to remove the content of the <body> element to generate an XSD",
                                                                resource.getFile())),
                                     e);
    }
    final Document transformedModuleDocument = schemaValidatingDocumentLoader(NoOpXmlErrorHandler::new)
        .loadDocument(() -> XMLSecureFactories.createDefault().getSAXParserFactory(),
                      new ModuleDelegatingEntityResolver(extensionModelHelper.getExtensionsModels()), resource.getFile(),
                      new ByteArrayInputStream(resultStream.toByteArray()));
    if (StringUtils.isNotBlank(transformedModuleDocument.getDocumentElement().getAttribute(XMLNS_TNS))) {
      final ExtensionDeclarer extensionDeclarer = new ExtensionDeclarer();
      loadModuleExtension(extensionDeclarer, resource, transformedModuleDocument, extensionModelHelper, true);
      result = createExtensionModel(extensionDeclarer);
    }
    return ofNullable(result);
  }

  private ComponentAst getModuleComponentModel(URL resource, Document moduleDocument, Set<ExtensionModel> extensions) {
    XmlApplicationParser xmlApplicationParser =
        new XmlApplicationParser(XmlNamespaceInfoProviderSupplier.createFromExtensionModels(extensions, Optional.empty()));
    Optional<ConfigLine> parseModule = xmlApplicationParser.parse(moduleDocument.getDocumentElement());
    if (!parseModule.isPresent()) {
      // This happens in org.mule.runtime.config.dsl.processor.xml.XmlApplicationParser.configLineFromElement()
      throw new IllegalArgumentException(format("There was an issue trying to read the stream of '%s'", resource.getFile()));
    }
    final ConfigLine configLine = parseModule.get();
    final ConfigurationPropertiesResolver externalPropertiesResolver = getConfigurationPropertiesResolver(configLine);
    final ComponentModelReader componentModelReader = new ComponentModelReader(externalPropertiesResolver);
    return componentModelReader.extractComponentDefinitionModel(configLine, modulePath);
  }

  private ConfigurationPropertiesResolver getConfigurationPropertiesResolver(ConfigLine configLine) {
    // <mule:global-property ... /> properties reader
    final ConfigurationPropertiesResolver globalPropertiesConfigurationPropertiesResolver =
        new DefaultConfigurationPropertiesResolver(of(new XmlExtensionConfigurationPropertiesResolver()),
                                                   createProviderFromGlobalProperties(configLine, modulePath));
    // system properties, such as "file.separator" properties reader
    final ConfigurationPropertiesResolver systemPropertiesResolver =
        new DefaultConfigurationPropertiesResolver(of(globalPropertiesConfigurationPropertiesResolver),
                                                   new EnvironmentPropertiesConfigurationProvider());
    // "file::" properties reader
    final String description = format("External files for smart connector '%s'", modulePath);
    final FileConfigurationPropertiesProvider externalPropertiesConfigurationProvider =
        new FileConfigurationPropertiesProvider(new ClassLoaderResourceProvider(currentThread().getContextClassLoader()),
                                                description);
    return new DefaultConfigurationPropertiesResolver(of(systemPropertiesResolver),
                                                      externalPropertiesConfigurationProvider);
  }

  private ConfigurationPropertiesProvider createProviderFromGlobalProperties(ConfigLine moduleLine, String modulePath) {
    final Map<String, ConfigurationProperty> globalProperties = new HashMap<>();
    moduleLine.getChildren().stream()
        .filter(configLine -> GLOBAL_PROPERTY.equals(configLine.getIdentifier()))
        .forEach(configLine -> {
          final String key = configLine.getConfigAttributes().get("name").getValue();
          final String rawValue = configLine.getConfigAttributes().get("value").getValue();
          globalProperties.put(key,
                               new DefaultConfigurationProperty(format("global-property - file: %s - lineNumber %s",
                                                                       modulePath, configLine.getLineNumber()),
                                                                key, rawValue));

        });
    return new GlobalPropertyConfigurationPropertiesProvider(globalProperties);
  }

  private void loadModuleExtension(ExtensionDeclarer declarer, URL resource, Document moduleDocument,
                                   ExtensionModelHelper extensionModelHelper, boolean comesFromTNS) {
    ComponentAst moduleModel =
        getModuleComponentModel(resource, moduleDocument, extensionModelHelper.getExtensionsModels());
    if (!moduleModel.getIdentifier().equals(MODULE_IDENTIFIER)) {
      throw new MuleRuntimeException(createStaticMessage(format("The root element of a module must be '%s', but found '%s'",
                                                                MODULE_IDENTIFIER.toString(),
                                                                moduleModel.getIdentifier().toString())));
    }

    moduleModel.recursiveStream()
        .forEach(componentModel -> resolveTypedComponentIdentifier((ComponentModel) componentModel, extensionModelHelper, true));
    recursiveStreamWithHierarchy(moduleModel).forEach(new ComponentLocationVisitor());

    final String name = moduleModel.getRawParameterValue(MODULE_NAME).orElse(null);
    final String version = "4.0.0"; // TODO(fernandezlautaro): MULE-11010 remove version from ExtensionModel
    final String category = moduleModel.getRawParameterValue(CATEGORY).orElse(null);
    final String vendor = moduleModel.getRawParameterValue(VENDOR).orElse(null);
    final XmlDslModel xmlDslModel = comesFromTNS
        ? getTnsXmlDslModel(moduleModel, name, version)
        : getXmlDslModel(moduleModel, name, version);
    final String description = getDescription(moduleModel);
    final String xmlnsTnsValue = moduleModel.getRawParameterValue(XMLNS_TNS).orElse(null);
    if (!comesFromTNS && xmlnsTnsValue != null && !xmlDslModel.getNamespace().equals(xmlnsTnsValue)) {
      throw new MuleRuntimeException(createStaticMessage(format("The %s attribute value of the module must be '%s', but found '%s'",
                                                                XMLNS_TNS,
                                                                xmlDslModel.getNamespace(),
                                                                xmlnsTnsValue)));
    }
    resourcesPaths.stream().forEach(declarer::withResource);

    fillDeclarer(declarer, name, version, category, vendor, xmlDslModel, description);
    declarer.withModelProperty(getXmlExtensionModelProperty((ComponentModel) moduleModel, xmlDslModel));

    Graph<String, DefaultEdge> directedGraph = new DefaultDirectedGraph<>(DefaultEdge.class);
    // loading public operations
    final List<ComponentAst> globalElementsComponentModel = extractGlobalElementsFrom(moduleModel);
    addGlobalElementModelProperty(declarer, globalElementsComponentModel);
    final Optional<ConfigurationDeclarer> configurationDeclarer =
        loadPropertiesFrom(declarer, moduleModel, globalElementsComponentModel, extensionModelHelper);
    final HasOperationDeclarer hasOperationDeclarer = configurationDeclarer.isPresent() ? configurationDeclarer.get() : declarer;

    ExtensionDeclarer temporalPublicOpsDeclarer = new ExtensionDeclarer();
    fillDeclarer(temporalPublicOpsDeclarer, name, version, category, vendor, xmlDslModel, description);
    loadOperationsFrom(hasOperationDeclarer, moduleModel, directedGraph, xmlDslModel, OperationVisibility.PUBLIC);
    loadOperationsFrom(temporalPublicOpsDeclarer, moduleModel, directedGraph, xmlDslModel,
                       OperationVisibility.PUBLIC);
    try {
      enrichModuleModel(moduleModel, createExtensionModel(temporalPublicOpsDeclarer), extensionModelHelper);
    } catch (IllegalModelDefinitionException e) {
      // Nothing to do, this failure will be thrown again when the actual declarer, hasOperationDeclarer, is used to build the
      // extension model.
    }

    // loading private operations
    if (comesFromTNS) {
      // when parsing for the TNS, we need the <operation/>s to be part of the extension model to validate the XML properly
      loadOperationsFrom(hasOperationDeclarer, moduleModel, directedGraph, xmlDslModel,
                         OperationVisibility.PRIVATE);
    } else {
      // when parsing for the macro expansion, the <operation/>s will be left in the PrivateOperationsModelProperty model property
      ExtensionDeclarer temporalPrivateOpsDeclarer = new ExtensionDeclarer();
      fillDeclarer(temporalPrivateOpsDeclarer, name, version, category, vendor, xmlDslModel, description);
      loadOperationsFrom(temporalPrivateOpsDeclarer, moduleModel, directedGraph, xmlDslModel,
                         OperationVisibility.PRIVATE);
      final ExtensionModel result = createExtensionModel(temporalPrivateOpsDeclarer);
      final PrivateOperationsModelProperty privateOperations = new PrivateOperationsModelProperty(result.getOperationModels());
      declarer.withModelProperty(privateOperations);

      enrichModuleModel(moduleModel, result, extensionModelHelper);
    }

    final CycleDetector<String, DefaultEdge> cycleDetector = new CycleDetector<>(directedGraph);
    final Set<String> cycles = cycleDetector.findCycles();
    if (!cycles.isEmpty()) {
      throw new MuleRuntimeException(createStaticMessage(format(CYCLIC_OPERATIONS_ERROR, new TreeSet<>(cycles))));
    }
  }

  public Stream<Pair<ComponentAst, List<ComponentAst>>> recursiveStreamWithHierarchy(ComponentAst moduleModel) {
    final List<Pair<ComponentAst, List<ComponentAst>>> ret = new ArrayList<>();

    final List<ComponentAst> currentContext = new ArrayList<>();

    ret.add(new Pair<>(moduleModel, new ArrayList<>(currentContext)));
    currentContext.add(moduleModel);

    moduleModel.directChildrenStream().forEach(cm -> {
      final List<ComponentAst> currentContextI = new ArrayList<>(currentContext);

      ret.add(new Pair<>(cm, new ArrayList<>(currentContextI)));
      currentContextI.add(cm);

      recursiveStreamWithHierarchy(ret, cm, currentContextI);
    });

    return ret.stream();
  }

  private void recursiveStreamWithHierarchy(final List<Pair<ComponentAst, List<ComponentAst>>> ret, ComponentAst cm,
                                            final List<ComponentAst> currentContext) {
    cm.directChildrenStream().forEach(cmi -> {
      final List<ComponentAst> currentContextI = new ArrayList<>(currentContext);

      ret.add(new Pair<>(cmi, new ArrayList<>(currentContextI)));
      currentContextI.add(cmi);

      recursiveStreamWithHierarchy(ret, cmi, currentContextI);
    });
  }

  private void enrichModuleModel(final ComponentAst moduleModel, ExtensionModel result,
                                 ExtensionModelHelper extensionModelHelper) {
    // TODO MULE-17419 (AST) Set all models, not just for operations (routers/scopes are missing here for sure)
    moduleModel.recursiveStream()
        .filter(comp -> TNS_PREFIX.equals(comp.getIdentifier().getNamespace()))
        .forEach(comp -> result.getOperationModel(comp.getIdentifier().getName())
            .ifPresent(model -> ((ComponentModel) comp).setComponentModel(model)));
  }

  private ExtensionModel createExtensionModel(ExtensionDeclarer declarer) {
    return new ExtensionModelFactory()
        .create(new DefaultExtensionLoadingContext(declarer, currentThread().getContextClassLoader(),
                                                   new NullDslResolvingContext()));
  }

  private void fillDeclarer(ExtensionDeclarer declarer, String name, String version, String category, String vendor,
                            XmlDslModel xmlDslModel, String description) {
    declarer.named(name)
        .describedAs(description)
        .fromVendor(vendor)
        .onVersion(version)
        .withCategory(Category.valueOf(category.toUpperCase()))
        .withXmlDsl(xmlDslModel);
  }

  /**
   * Calculates all the used namespaces of the given <module/> leaving behind the (possible) cyclic reference if there are
   * {@link MacroExpansionModuleModel#TNS_PREFIX} references by removing the current namespace generation.
   *
   * @param moduleModel XML of the <module/>
   * @param xmlDslModel the {@link XmlDslModel} for the current {@link ExtensionModel} generation
   * @return a {@link XmlExtensionModelProperty} which contains all the namespaces dependencies. Among them could be dependencies
   * that must be macro expanded and others which might not, but that job is left for the
   * {@link MacroExpansionModulesModel#getDirectExpandableNamespaceDependencies(ComponentModel, Set)}
   */
  private XmlExtensionModelProperty getXmlExtensionModelProperty(ComponentModel moduleModel,
                                                                 XmlDslModel xmlDslModel) {
    final Set<String> namespaceDependencies = getUsedNamespaces(moduleModel).stream()
        .filter(namespace -> !xmlDslModel.getNamespace().equals(namespace))
        .collect(toSet());
    return new XmlExtensionModelProperty(namespaceDependencies);
  }

  private XmlDslModel getTnsXmlDslModel(ComponentAst moduleModel, String name, String version) {
    final Optional<String> namespace = moduleModel.getRawParameterValue(XMLNS_TNS);
    final String stringPrefix = TNS_PREFIX;

    final Map<String, String> schemaLocations = moduleModel.getRawParameterValue("xsi:schemaLocation")
        .map(schLoc -> {
          Map<String, String> myMap = new HashMap<>();
          String[] pairs = schLoc.split(" ");
          for (int i = 0; i < pairs.length; i = i + 2) {
            myMap.put(pairs[i], pairs[i + 1]);
          }
          return myMap;
        })
        .orElse(emptyMap());

    final String[] tnsSchemaLocationParts = schemaLocations.get(namespace.get()).split("/");

    return XmlDslModel.builder()
        .setSchemaVersion(version)
        .setPrefix(stringPrefix)
        .setNamespace(namespace.get())
        .setSchemaLocation(schemaLocations.get(namespace.get()))
        .setXsdFileName(tnsSchemaLocationParts[tnsSchemaLocationParts.length - 1])
        .build();
  }

  private XmlDslModel getXmlDslModel(ComponentAst moduleModel, String name, String version) {
    final Optional<String> prefix = moduleModel.getRawParameterValue(MODULE_PREFIX_ATTRIBUTE);
    final Optional<String> namespace = moduleModel.getRawParameterValue(MODULE_NAMESPACE_ATTRIBUTE);
    return createXmlLanguageModel(prefix, namespace, name, version);
  }

  private String getDescription(ComponentAst componentModel) {
    return componentModel.getRawParameterValue(DOC_DESCRIPTION).orElse("");
  }

  private List<ComponentAst> extractGlobalElementsFrom(ComponentAst moduleModel) {
    final Set<ComponentIdentifier> NOT_GLOBAL_ELEMENT_IDENTIFIERS = Sets
        .newHashSet(OPERATION_PROPERTY_IDENTIFIER, CONNECTION_PROPERTIES_IDENTIFIER, OPERATION_IDENTIFIER);
    return moduleModel.directChildrenStream()
        .filter(child -> !NOT_GLOBAL_ELEMENT_IDENTIFIERS.contains(child.getIdentifier()))
        .collect(toList());
  }

  private Optional<ConfigurationDeclarer> loadPropertiesFrom(ExtensionDeclarer declarer, ComponentAst moduleModel,
                                                             List<ComponentAst> globalElementsComponentModel,
                                                             ExtensionModelHelper extensionModelHelper) {
    List<ComponentAst> configurationProperties = extractProperties(moduleModel);
    List<ComponentAst> connectionProperties = extractConnectionProperties(moduleModel);
    validateProperties(configurationProperties, connectionProperties);

    if (!configurationProperties.isEmpty() || !connectionProperties.isEmpty()) {
      declarer.withModelProperty(new NoReconnectionStrategyModelProperty());
      ConfigurationDeclarer configurationDeclarer = declarer.withConfig(CONFIG_NAME);
      configurationProperties.forEach(param -> extractProperty(configurationDeclarer, param));
      addConnectionProvider(configurationDeclarer, connectionProperties, globalElementsComponentModel, extensionModelHelper);
      return of(configurationDeclarer);
    }
    return empty();
  }

  private void addGlobalElementModelProperty(ExtensionDeclarer declarer, List<ComponentAst> globalElementsComponentModel) {
    if (!globalElementsComponentModel.isEmpty()) {
      declarer.withModelProperty(new GlobalElementComponentModelModelProperty(globalElementsComponentModel));
    }
  }

  private List<ComponentAst> extractProperties(ComponentAst moduleModel) {
    return moduleModel.directChildrenStream()
        .filter(child -> child.getIdentifier().equals(OPERATION_PROPERTY_IDENTIFIER))
        .collect(toList());
  }

  private List<ComponentAst> extractConnectionProperties(ComponentAst moduleModel) {
    final List<ComponentAst> connectionsComponentModel = moduleModel.directChildrenStream()
        .filter(child -> child.getIdentifier().equals(CONNECTION_PROPERTIES_IDENTIFIER))
        .collect(toList());
    if (connectionsComponentModel.size() > 1) {
      throw new MuleRuntimeException(createStaticMessage(format("There cannot be more than 1 child [%s] element per [%s], found [%d]",
                                                                CONNECTION_PROPERTIES_IDENTIFIER.getName(),
                                                                MODULE_IDENTIFIER.getName(),
                                                                connectionsComponentModel.size())));
    }
    return connectionsComponentModel.isEmpty() ? emptyList() : extractProperties(connectionsComponentModel.get(0));
  }

  /**
   * Throws exception if a <property/> for a configuration or connection have the same name.
   *
   * @param configurationProperties properties that will go in the configuration
   * @param connectionProperties    properties that will go in the connection
   */
  private void validateProperties(List<ComponentAst> configurationProperties, List<ComponentAst> connectionProperties) {
    final List<String> connectionPropertiesNames =
        connectionProperties.stream()
            .map(ComponentAst::getComponentId)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .collect(toList());
    List<String> intersectedProperties = configurationProperties.stream()
        .map(ComponentAst::getComponentId)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .filter(connectionPropertiesNames::contains)
        .collect(toList());
    if (!intersectedProperties.isEmpty()) {
      throw new MuleRuntimeException(createStaticMessage(format("There cannot be properties with the same name even if they are within a <connection>, repeated properties are: [%s]",
                                                                intersectedProperties.stream()
                                                                    .collect(joining(", ")))));
    }
  }

  /**
   * Adds a connection provider if (a) there's at least one global element that has test connection or (b) there's at least one
   * <property/> that has been placed within a <connection/> wrapper in the <module/> element.
   *
   * @param configurationDeclarer        declarer to add the {@link ConnectionProviderDeclarer} if applies.
   * @param connectionProperties         collection of <property/>s that should be added to the {@link ConnectionProviderDeclarer}.
   * @param globalElementsComponentModel collection of global elements where through
   *                                     {@link #getTestConnectionGlobalElement(ConfigurationDeclarer, List, Set)} will look for one that supports test
   *                                     connectivity.
   * @param extensionModelHelper         used also in
   *                                     {@link #getTestConnectionGlobalElement(ConfigurationDeclarer, List, ExtensionModelHelper)}, through the
   *                                     {@link #findTestConnectionGlobalElementFrom}, as the XML of the extensions might change of the values that the
   *                                     {@link ExtensionModel} has (heavily relies on the {@link ExtensionModelHelper}).
   */
  private void addConnectionProvider(ConfigurationDeclarer configurationDeclarer,
                                     List<ComponentAst> connectionProperties,
                                     List<ComponentAst> globalElementsComponentModel,
                                     ExtensionModelHelper extensionModelHelper) {
    final Optional<ComponentAst> testConnectionGlobalElementOptional =
        getTestConnectionGlobalElement(configurationDeclarer, globalElementsComponentModel, extensionModelHelper);

    if (testConnectionGlobalElementOptional.isPresent() || !connectionProperties.isEmpty()) {
      final ConnectionProviderDeclarer connectionProviderDeclarer =
          configurationDeclarer.withConnectionProvider(MODULE_CONNECTION_GLOBAL_ELEMENT_NAME);
      connectionProviderDeclarer
          .withConnectionManagementType(ConnectionManagementType.NONE);
      connectionProperties.stream().forEach(param -> extractProperty(connectionProviderDeclarer, param));

      testConnectionGlobalElementOptional.ifPresent(testConnectionGlobalElement -> testConnectionGlobalElement
          .getRawParameterValue(GLOBAL_ELEMENT_NAME_ATTRIBUTE)
          .ifPresent(testConnectionGlobalElementName -> connectionProviderDeclarer
              .withModelProperty(new TestConnectionGlobalElementModelProperty(testConnectionGlobalElementName))));
    }

  }

  private Optional<ComponentAst> getTestConnectionGlobalElement(ConfigurationDeclarer configurationDeclarer,
                                                                List<ComponentAst> globalElementsComponentModel,
                                                                ExtensionModelHelper extensionModelHelper) {
    final List<ComponentAst> markedAsTestConnectionGlobalElements =
        globalElementsComponentModel.stream()
            .filter(globalElementComponentModel -> globalElementComponentModel
                .getRawParameterValue(MODULE_CONNECTION_MARKER_ATTRIBUTE).map(Boolean::parseBoolean).orElse(false))
            .collect(toList());

    if (markedAsTestConnectionGlobalElements.size() > 1) {
      throw new MuleRuntimeException(createStaticMessage(format("It can only be one global element marked as test connectivity [%s] but found [%d], offended global elements are: [%s]",
                                                                MODULE_CONNECTION_MARKER_ATTRIBUTE,
                                                                markedAsTestConnectionGlobalElements.size(),
                                                                markedAsTestConnectionGlobalElements.stream()
                                                                    .map(ComponentAst::getComponentId)
                                                                    .filter(Optional::isPresent)
                                                                    .map(Optional::get)
                                                                    .collect(joining(", ")))));
    }
    Optional<ComponentAst> testConnectionGlobalElement = markedAsTestConnectionGlobalElements.stream().findFirst();
    if (!testConnectionGlobalElement.isPresent()) {
      testConnectionGlobalElement = findTestConnectionGlobalElementFrom(globalElementsComponentModel, extensionModelHelper);
    } else {
      // validates that the MODULE_CONNECTION_MARKER_ATTRIBUTE is on a correct XML element that supports test connection
      Optional<ComponentAst> temporalTestConnectionGlobalElement =
          findTestConnectionGlobalElementFrom(singletonList(testConnectionGlobalElement.get()), extensionModelHelper);
      if ((!temporalTestConnectionGlobalElement.isPresent())
          || (!temporalTestConnectionGlobalElement.get().equals(testConnectionGlobalElement.get()))) {
        configurationDeclarer.withModelProperty(new InvalidTestConnectionMarkerModelProperty(testConnectionGlobalElement
            .flatMap(ComponentAst::getComponentId).orElse(null), testConnectionGlobalElement.get().getIdentifier().toString()));
      }
    }
    return testConnectionGlobalElement;
  }

  /**
   * Goes over all {@code globalElementsComponentModel} looking for the configuration and connection elements (parent and child),
   * where if present looks for the {@link ExtensionModel}s validating if the element is in fact a {@link ConnectionProvider}. It
   * heavily relies on the {@link DslSyntaxResolver}, as many elements in the XML do not match to the names of the model.
   *
   * @param globalElementsComponentModel global elements of the smart connector
   * @param extensionModelHelper         with the set of extensions used to generate the current {@link ExtensionModel}
   * @return a {@link ComponentModel} of the global element to do test connection, empty otherwise.
   */
  private Optional<ComponentAst> findTestConnectionGlobalElementFrom(List<ComponentAst> globalElementsComponentModel,
                                                                     ExtensionModelHelper extensionModelHelper) {
    final List<ComponentAst> testConnectionComponentModels = globalElementsComponentModel
        .stream()
        .filter(globalElementComponentModel -> globalElementComponentModel.getModel(ConfigurationModel.class)
            .map(configurationModel -> configurationModel.getConnectionProviders().stream()
                .anyMatch(ConnectionProviderModel::supportsConnectivityTesting))
            .orElse(false))
        .collect(toList());

    if (testConnectionComponentModels.size() > 1) {
      throw new MuleRuntimeException(createStaticMessage(format("There are [%d] global elements that can be potentially used for test connection when it should be just one. Mark any of them with the attribute [%s=\"true\"], offended global elements are: [%s]",
                                                                testConnectionComponentModels.size(),
                                                                MODULE_CONNECTION_MARKER_ATTRIBUTE,
                                                                testConnectionComponentModels.stream()
                                                                    .map(ComponentAst::getComponentId)
                                                                    .filter(Optional::isPresent)
                                                                    .map(Optional::get)
                                                                    .sorted()
                                                                    .collect(joining(", ")))));
    }
    return testConnectionComponentModels.stream().findFirst();
  }

  private void loadOperationsFrom(HasOperationDeclarer declarer, ComponentAst moduleModel,
                                  Graph<String, DefaultEdge> directedGraph, XmlDslModel xmlDslModel,
                                  final OperationVisibility visibility) {

    moduleModel.directChildrenStream()
        .filter(child -> child.getIdentifier().equals(OPERATION_IDENTIFIER))
        .filter(operationModel -> operationModel.getRawParameterValue(ATTRIBUTE_VISIBILITY).map(OperationVisibility::valueOf)
            .orElse(null) == visibility)
        .forEach(operationModel -> extractOperationExtension(declarer, operationModel, directedGraph, xmlDslModel));
  }

  private void extractOperationExtension(HasOperationDeclarer declarer, ComponentAst operationModel,
                                         Graph<String, DefaultEdge> directedGraph, XmlDslModel xmlDslModel) {
    String operationName = operationModel.getComponentId().orElse(null);
    OperationDeclarer operationDeclarer = declarer.withOperation(operationName);
    ComponentAst bodyComponentModel = operationModel.directChildrenStream()
        .filter(child -> child.getIdentifier().equals(OPERATION_BODY_IDENTIFIER)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException(format("The operation '%s' is missing the <body> statement",
                                                               operationName)));

    directedGraph.addVertex(operationName);
    fillGraphWithTnsReferences(directedGraph, operationName, bodyComponentModel.directChildrenStream());

    operationDeclarer.withModelProperty(new OperationComponentModelModelProperty(operationModel, bodyComponentModel));
    operationDeclarer.describedAs(getDescription(operationModel));
    operationDeclarer.getDeclaration().setDisplayModel(getDisplayModel(operationModel));
    extractOperationParameters(operationDeclarer, operationModel);
    extractOutputType(operationDeclarer.withOutput(), OPERATION_OUTPUT_IDENTIFIER, operationModel,
                      getDeclarationOutputFor(operationName));
    extractOutputType(operationDeclarer.withOutputAttributes(), OPERATION_OUTPUT_ATTRIBUTES_IDENTIFIER, operationModel,
                      getDeclarationOutputAttributesFor(operationName));
    declareErrorModels(operationDeclarer, xmlDslModel, operationName, operationModel);
  }

  private Optional<MetadataType> getDeclarationOutputFor(String operationName) {
    Optional<MetadataType> result = Optional.empty();
    if (declarationMap.containsKey(operationName)) {
      result = Optional.of(declarationMap.get(operationName).getOutput());
    }
    return result;
  }

  private Optional<MetadataType> getDeclarationOutputAttributesFor(String operationName) {
    Optional<MetadataType> result = Optional.empty();
    if (declarationMap.containsKey(operationName)) {
      result = Optional.of(declarationMap.get(operationName).getOutputAttributes());
    }
    return result;
  }

  /**
   * Goes over the {@code innerComponents} collection checking if any reference is a {@link MacroExpansionModuleModel#TNS_PREFIX},
   * in which case it adds an edge to the current vertex {@code sourceOperationVertex}
   *
   * @param directedGraph         graph to contain all the vertex operations and linkage with other operations
   * @param sourceOperationVertex current vertex we are working on
   * @param innerComponents       collection of elements to introspect and assembly the graph with
   */
  private void fillGraphWithTnsReferences(Graph<String, DefaultEdge> directedGraph, String sourceOperationVertex,
                                          final Stream<ComponentAst> innerComponents) {
    innerComponents.forEach(childMPComponentModel -> {
      if (TNS_PREFIX.equals(childMPComponentModel.getIdentifier().getNamespace())) {
        // we will take the current component model name, as any child of it are actually TNS child references (aka: parameters)
        final String targetOperationVertex = childMPComponentModel.getIdentifier().getName();
        if (!directedGraph.containsVertex(targetOperationVertex)) {
          directedGraph.addVertex(targetOperationVertex);
        }
        directedGraph.addEdge(sourceOperationVertex, targetOperationVertex);
      } else {
        // scenario for nested scopes that might be having cyclic references to operations
        childMPComponentModel.directChildrenStream()
            .forEach(childChildMPComponentModel -> fillGraphWithTnsReferences(directedGraph, sourceOperationVertex,
                                                                              childMPComponentModel.directChildrenStream()));
      }
    });
  }

  private void extractOperationParameters(OperationDeclarer operationDeclarer, ComponentAst componentModel) {
    Optional<ComponentAst> optionalParametersComponentModel = componentModel.directChildrenStream()
        .filter(child -> child.getIdentifier().equals(OPERATION_PARAMETERS_IDENTIFIER)).findAny();
    if (optionalParametersComponentModel.isPresent()) {
      optionalParametersComponentModel.get().directChildrenStream()
          .filter(child -> child.getIdentifier().equals(OPERATION_PARAMETER_IDENTIFIER))
          .forEach(param -> {
            final String role = param.getRawParameterValue(ROLE).orElse(null);
            extractParameter(operationDeclarer, param, getRole(role));
          });
    }
  }

  private void extractProperty(ParameterizedDeclarer parameterizedDeclarer, ComponentAst param) {
    extractParameter(parameterizedDeclarer, param, BEHAVIOUR);
  }

  private void extractParameter(ParameterizedDeclarer parameterizedDeclarer, ComponentAst param, ParameterRole role) {
    String receivedInputType = param.getRawParameterValue(TYPE_ATTRIBUTE).orElse(null);
    final LayoutModel.LayoutModelBuilder layoutModelBuilder = builder();
    if (parseBoolean(param.getRawParameterValue(PASSWORD).orElse(null))) {
      layoutModelBuilder.asPassword();
    }
    layoutModelBuilder.order(getOrder(param.getRawParameterValue(ORDER_ATTRIBUTE).orElse(null)));
    layoutModelBuilder.tabName(getTab(param.getRawParameterValue(TAB_ATTRIBUTE).orElse(null)));

    final DisplayModel displayModel = getDisplayModel(param);
    MetadataType parameterType = extractType(receivedInputType);

    ParameterDeclarer parameterDeclarer = getParameterDeclarer(parameterizedDeclarer, param);
    parameterDeclarer.describedAs(getDescription(param))
        .withLayout(layoutModelBuilder.build())
        .withDisplayModel(displayModel)
        .withRole(role)
        .ofType(parameterType);
  }

  private DisplayModel getDisplayModel(ComponentAst componentModel) {
    final DisplayModel.DisplayModelBuilder displayModelBuilder = DisplayModel.builder();
    displayModelBuilder.displayName(componentModel.getRawParameterValue(DISPLAY_NAME_ATTRIBUTE).orElse(null));
    displayModelBuilder.summary(componentModel.getRawParameterValue(SUMMARY_ATTRIBUTE).orElse(null));
    displayModelBuilder.example(componentModel.getRawParameterValue(EXAMPLE_ATTRIBUTE).orElse(null));
    return displayModelBuilder.build();
  }

  private String getTab(String tab) {
    return StringUtils.isBlank(tab) ? Placement.DEFAULT_TAB : tab;
  }

  private int getOrder(final String order) {
    try {
      return Integer.parseInt(order);
    } catch (NumberFormatException e) {
      return Placement.DEFAULT_ORDER;
    }
  }

  /**
   * Giving a {@link ParameterDeclarer} for the parameter and the attributes in the {@code parameters}, this method will verify
   * the rules for the {@link #ATTRIBUTE_USE} where:
   * <ul>
   * <li>{@link UseEnum#REQUIRED} marks the attribute as required in the XSD, failing if leaved empty when consuming the
   * parameter/property. It can not be {@link UseEnum#REQUIRED} if the parameter/property has a {@link #PARAMETER_DEFAULT_VALUE}
   * attribute</li>
   * <li>{@link UseEnum#OPTIONAL} marks the attribute as optional in the XSD. Can be {@link UseEnum#OPTIONAL} if the
   * parameter/property has a {@link #PARAMETER_DEFAULT_VALUE} attribute</li>
   * <li>{@link UseEnum#AUTO} will default at runtime to {@link UseEnum#REQUIRED} if {@link #PARAMETER_DEFAULT_VALUE} attribute is
   * absent, otherwise it will be marked as {@link UseEnum#OPTIONAL}</li>
   * </ul>
   *
   * @param parameterizedDeclarer builder to declare the {@link ParameterDeclarer}
   * @param param attributes to consume the values from
   * @return the {@link ParameterDeclarer}, being created as required or optional with a default value if applies.
   */
  private ParameterDeclarer getParameterDeclarer(ParameterizedDeclarer parameterizedDeclarer, ComponentAst param) {
    final String parameterName = param.getRawParameterValue(PARAMETER_NAME).orElse(null);
    final Optional<String> parameterDefaultValue = param.getRawParameterValue(PARAMETER_DEFAULT_VALUE);
    final UseEnum use = UseEnum.valueOf(param.getRawParameterValue(ATTRIBUTE_USE).orElse(null));
    if (UseEnum.REQUIRED.equals(use) && parameterDefaultValue.isPresent()) {
      throw new IllegalParameterModelDefinitionException(format("The parameter [%s] cannot have the %s attribute set to %s when it has a default value",
                                                                parameterName, ATTRIBUTE_USE, UseEnum.REQUIRED));
    }
    // Is required if either is marked as REQUIRED or it's marked as AUTO an doesn't have a default value
    boolean parameterRequired = UseEnum.REQUIRED.equals(use) || (UseEnum.AUTO.equals(use) && !parameterDefaultValue.isPresent());
    return parameterRequired ? parameterizedDeclarer.onDefaultParameterGroup().withRequiredParameter(parameterName)
        : parameterizedDeclarer.onDefaultParameterGroup().withOptionalParameter(parameterName)
            .defaultingTo(parameterDefaultValue.orElse(null));
  }

  private void extractOutputType(OutputDeclarer outputDeclarer, ComponentIdentifier componentIdentifier,
                                 ComponentAst operationModel, Optional<MetadataType> calculatedOutput) {
    Optional<ComponentAst> outputAttributesComponentModel = operationModel.directChildrenStream()
        .filter(child -> child.getIdentifier().equals(componentIdentifier)).findFirst();
    outputAttributesComponentModel
        .ifPresent(outputComponentModel -> outputDeclarer.describedAs(getDescription(outputComponentModel)));

    MetadataType metadataType = getMetadataType(outputAttributesComponentModel, calculatedOutput);
    outputDeclarer.ofType(metadataType);
  }

  private MetadataType getMetadataType(Optional<ComponentAst> outputAttributesComponentModel,
                                       Optional<MetadataType> declarationMetadataType) {
    MetadataType metadataType;
    // the calculated metadata has precedence over the one configured in the xml
    if (declarationMetadataType.isPresent()) {
      metadataType = declarationMetadataType.get();
    } else {
      // if tye element is absent, it will default to the VOID type
      if (outputAttributesComponentModel.isPresent()) {
        String receivedOutputAttributeType =
            outputAttributesComponentModel.get().getRawParameterValue(TYPE_ATTRIBUTE).orElse(null);
        metadataType = extractType(receivedOutputAttributeType);
      } else {
        metadataType = BaseTypeBuilder.create(JAVA).voidType().build();
      }
    }
    return metadataType;
  }

  private MetadataType extractType(String receivedType) {
    Optional<MetadataType> metadataType = empty();
    try {
      metadataType = typeResolver.resolveType(receivedType);
    } catch (TypeResolverException e) {
      if (!metadataType.isPresent()) {
        throw new IllegalParameterModelDefinitionException(format("The type obtained [%s] cannot be resolved", receivedType), e);
      }
    }
    if (!metadataType.isPresent()) {
      String errorMessage = format(
                                   "should not have reach here. Type obtained [%s] when supported default types are [%s].",
                                   receivedType,
                                   join(", ", PRIMITIVE_TYPES.keySet()));
      throw new IllegalParameterModelDefinitionException(errorMessage);
    }
    return metadataType.get();
  }

  private void declareErrorModels(OperationDeclarer operationDeclarer, XmlDslModel xmlDslModel, String operationName,
                                  ComponentAst operationModel) {
    Optional<ComponentAst> optionalParametersComponentModel = operationModel.directChildrenStream()
        .filter(child -> child.getIdentifier().equals(OPERATION_ERRORS_IDENTIFIER)).findAny();
    optionalParametersComponentModel.ifPresent(componentModel -> componentModel.directChildrenStream()
        .filter(child -> child.getIdentifier().equals(OPERATION_ERROR_IDENTIFIER))
        .forEach(param -> {
          final String namespace = xmlDslModel.getPrefix().toUpperCase();
          param.getRawParameterValue(ERROR_TYPE_ATTRIBUTE)
              .map(tn -> {
                if (tn.contains(NAMESPACE_SEPARATOR)) {
                  throw new IllegalModelDefinitionException(format("The operation [%s] cannot have an <error> [%s] that contains a reserved character [%s]",
                                                                   operationName, tn,
                                                                   NAMESPACE_SEPARATOR));
                }
                return operationDeclarer.withErrorModel(ErrorModelBuilder.newError(tn, namespace)
                    .withParent(ErrorModelBuilder.newError(ANY).build())
                    .build());
              })
              .orElseThrow(() -> new IllegalModelDefinitionException(format("The operation [%s] cannot have an <error> with an empty 'type' attribute",
                                                                            operationName)));
        }));
  }

  /**
   * Utility class to read the XML module entirely so that if there's any usage of configurations properties, such as
   * "${someProperty}", the {@link ForbiddenConfigurationPropertiesValidator} can show the errors consistently, Without this dull
   * implementation, the {@link ComponentModelReader#extractComponentDefinitionModel(ConfigLine, String)} method fails while
   * reading ANY parametrization throwing a {@link PropertyNotFoundException} eagerly.
   */
  private class XmlExtensionConfigurationPropertiesResolver implements ConfigurationPropertiesResolver {

    @Override
    public Object resolveValue(String value) {
      return value;
    }

    @Override
    public Object resolvePlaceholderKeyValue(String placeholderKey) {
      return placeholderKey;
    }
  }
}