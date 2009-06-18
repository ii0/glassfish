/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.javaee.full.deployment;

import com.sun.enterprise.module.Module;
import java.io.OutputStream;
import org.glassfish.api.deployment.*;
import org.glassfish.api.deployment.archive.ReadableArchive;
import org.glassfish.api.container.Container;
import org.glassfish.api.ActionReport;
import org.glassfish.api.event.Events;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.api.container.Sniffer;
import org.glassfish.deployment.common.DownloadableArtifacts;
import org.glassfish.internal.deployment.Deployment;
import org.glassfish.internal.deployment.ExtendedDeploymentContext;
import org.glassfish.internal.data.*;
import org.glassfish.internal.deployment.SnifferManager;
import org.glassfish.deployment.common.DeploymentContextImpl;
import org.glassfish.deployment.common.DeploymentUtils;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Scoped;
import org.jvnet.hk2.component.Habitat;
import org.jvnet.hk2.component.PerLookup;
import com.sun.enterprise.deployment.*;
import com.sun.enterprise.deployment.util.ModuleDescriptor;
import com.sun.enterprise.deployment.util.XModuleType;
import com.sun.enterprise.deploy.shared.ArchiveFactory;
import com.sun.enterprise.deployment.deploy.shared.OutputJarArchive;
import com.sun.enterprise.deployment.deploy.shared.Util;
import com.sun.enterprise.module.ModulesRegistry;
import com.sun.enterprise.universal.io.FileUtils;
import com.sun.logging.LogDomains;

import java.util.*;
import java.util.logging.Logger;
import java.io.IOException;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.glassfish.deployment.common.DeploymentException;
import org.glassfish.deployment.common.DummyApplication;
import org.jvnet.hk2.component.PostConstruct;

/**
 * EarDeployer to deploy composite Java EE applications.
 * todo : could be generified into any composite applications.
 *
 * @author Jerome Dochez
 */
@Service
@Scoped(PerLookup.class)
public class EarDeployer implements Deployer, PostConstruct {

//    private static final Class GLASSFISH_APPCLIENT_GROUP_FACADE_CLASS =
//            org.glassfish.appclient.client.AppClientGroupFacade.class;
// Currently using a string instead of a Class constant to avoid a circular
// dependency.  
    private static final String GLASSFISH_APPCLIENT_GROUP_FACADE_CLASS_NAME =
            "org.glassfish.appclient.client.AppClientGroupFacade";

    private static final Attributes.Name GLASSFISH_APPCLIENT_GROUP = new Attributes.Name("GlassFish-AppClient-Group");
    private static final String GF_CLIENT_MODULE_NAME = "org.glassfish.appclient.gf-client-module";

    @Inject
    Habitat habitat;

    @Inject
    Deployment deployment;

    @Inject
    ServerEnvironment env;

    @Inject
    ApplicationRegistry appRegistry;

    @Inject
    protected SnifferManager snifferManager;

    @Inject
    ArchiveFactory archiveFactory;

    @Inject
    Events events;

    @Inject
    private DownloadableArtifacts artifacts;

    @Inject
    private ModulesRegistry modulesRegistry;

    private ClassLoader gfClientModuleClassLoader;

    public void postConstruct() {
        for (Module module : modulesRegistry.getModules(GF_CLIENT_MODULE_NAME)) {
            gfClientModuleClassLoader = module.getClassLoader();
        }
    }



    final static Logger logger = LogDomains.getLogger(DeploymentUtils.class, LogDomains.DPL_LOGGER);
    
    public MetaData getMetaData() {
        return new MetaData(false, null, new Class[] { Application.class});
    }

    public Object loadMetaData(Class type, DeploymentContext context) {
        return null;
    }

    public boolean prepare(final DeploymentContext context) {

        final Application application = context.getModuleMetaData(Application.class);

        DeployCommandParameters deployParams = context.getCommandParameters(DeployCommandParameters.class);
        final String appName = deployParams.name();
        
        final ApplicationInfo appInfo = new CompositeApplicationInfo(events, application, context.getSource(), appName);
        for (Object m : context.getModuleMetadata()) {
            appInfo.addMetaData(m);
        }

        try {
            doOnAllBundles(application, new BundleBlock<ModuleInfo>() {
                public ModuleInfo doBundle(ModuleDescriptor bundle) throws Exception {
                    ModuleInfo info = prepareBundle(bundle, application, subContext(application, context, bundle.getArchiveUri()));
                    appInfo.addModule(info);
                    return info;
                }

            });
        } catch(Exception e) {

        }

        context.addModuleMetaData(appInfo);
        generateArtifacts(context);
        return true;
    }

    protected void generateArtifacts(final DeploymentContext context) throws DeploymentException {
        /*
         * For EARs, currently only nested app clients will generate artifacts.
         */
        final Application application = context.getModuleMetaData(Application.class);
        final Collection<ModuleDescriptor<BundleDescriptor>> appClients =
                application.getModuleDescriptorsByType(XModuleType.CAR);

        final StringBuilder appClientGroupListSB = new StringBuilder();

        /*
         * For each app client, get its facade's URI to include in the
         * generated EAR facade's client group listing.
         */
        for (Iterator<ModuleDescriptor<BundleDescriptor>> it = appClients.iterator(); it.hasNext(); ) {
            ModuleDescriptor<BundleDescriptor> md = it.next();
            appClientGroupListSB.append((appClientGroupListSB.length() > 0) ? " " : "")
                    .append(earDirUserURI(context)).append(appClientFacadeUserURI(md.getArchiveUri()));
        }

        try {
            generateAndRecordEARFacade(
                    application.getRegistrationName(),
                    context.getScratchDir("xml"),
                    generatedEARFacadeName(application.getRegistrationName()), appClientGroupListSB.toString());
        } catch (Exception e) {
            throw new DeploymentException(e);
        }


    }

    private String earDirUserURI(final DeploymentContext dc) {
        final DeployCommandParameters deployParams = dc.getCommandParameters(DeployCommandParameters.class);
        final String appName = deployParams.name();
        return appName + "Client/";
    }

    private String appClientFacadeUserURI(String appClientModuleURIText) {
        if (appClientModuleURIText.endsWith("_jar")) {
            appClientModuleURIText = appClientModuleURIText.substring(0, appClientModuleURIText.lastIndexOf("_jar")) + ".jar";
        }
        final int dotJar = appClientModuleURIText.lastIndexOf(".jar");
        String appClientFacadePath = appClientModuleURIText.substring(0, dotJar) + "Client.jar";
        return appClientFacadePath;
    }

    private String generatedEARFacadeName(final String earName) {
        return earName + "Client.jar";
    }

    private void generateAndRecordEARFacade(final String earName,
            final File appScratchDir,
            final String facadeFileName,
            final String appClientGroupList) throws IOException {

        File generatedJar = new File(appScratchDir, facadeFileName);
        OutputJarArchive facadeArchive = new OutputJarArchive();
        facadeArchive.create(generatedJar.toURI());

        Manifest manifest = facadeArchive.getManifest();
        Attributes mainAttrs = manifest.getMainAttributes();

        mainAttrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mainAttrs.put(Attributes.Name.MAIN_CLASS, GLASSFISH_APPCLIENT_GROUP_FACADE_CLASS_NAME);
        mainAttrs.put(GLASSFISH_APPCLIENT_GROUP, appClientGroupList);


        //Now manifest is ready to be written into the facade jar
        OutputStream os = facadeArchive.putNextEntry(JarFile.MANIFEST_NAME);
        manifest.write(os);
        facadeArchive.closeEntry();

        final String mainClassResourceName =
                GLASSFISH_APPCLIENT_GROUP_FACADE_CLASS_NAME.replace('.', '/') +
                ".class";
        os = facadeArchive.putNextEntry(mainClassResourceName);

        try {
            InputStream is = openByteCodeStream("/" + mainClassResourceName);
            FileUtils.copyStream(is, os);
            is.close();
        } catch (Exception e) {
            throw new DeploymentException(e);
        }

        Set<DownloadableArtifacts.FullAndPartURIs> downloads =
                    new HashSet<DownloadableArtifacts.FullAndPartURIs>();
        downloads.add(new DownloadableArtifacts.FullAndPartURIs(generatedJar.toURI(), facadeFileName));
        artifacts.addArtifacts(earName, downloads);

    }

    protected InputStream openByteCodeStream(final String resourceName) throws IOException {
        URL url = gfClientModuleClassLoader.getResource(resourceName);
        if (url == null) {
            throw new IllegalArgumentException(resourceName);
        }
        InputStream is = url.openStream();
        return is;
    }
    
    private class CompositeApplicationInfo extends ApplicationInfo {

        final Application application;

        private CompositeApplicationInfo(Events events, Application application, ReadableArchive source, String name) {
            super(events, source, name);
            this.application = application;
        }

        @Override
        protected ExtendedDeploymentContext getSubContext(ModuleInfo module, ExtendedDeploymentContext context) {
            return subContext(application, context, module.getName());
        }

     }

    
    private Collection<ModuleDescriptor<BundleDescriptor>>
                doOnAllTypedBundles(Application application, XModuleType type, BundleBlock runnable)
                    throws Exception {

        final Collection<ModuleDescriptor<BundleDescriptor>> typedBundles = application.getModuleDescriptorsByType(type);
        for (ModuleDescriptor module : typedBundles) {
            runnable.doBundle(module);
        }
        return typedBundles;
    }

    private void doOnAllBundles(Application application, BundleBlock runnable) throws Exception {

        Collection<ModuleDescriptor> bundles = 
            new LinkedHashSet<ModuleDescriptor>();
        bundles.addAll(application.getModules());

        // if the initialize-in-order flag is set
        // we load the modules by their declaration order in application.xml
        if (application.isInitializeInOrder()) {
            for (final ModuleDescriptor bundle : bundles) {
                runnable.doBundle(bundle);
            }
        }
        
        // otherwise we load modules by default order: connector, ejb, web
        else {
            // first we take care of the connectors
            bundles.removeAll(doOnAllTypedBundles(application, XModuleType.RAR, runnable));

            // now the EJBs
            bundles.removeAll(doOnAllTypedBundles(application, XModuleType.EJB, runnable));

            // finally the war files.
            bundles.removeAll(doOnAllTypedBundles(application, XModuleType.WAR, runnable));

            // now ther remaining bundles
            for (final ModuleDescriptor bundle : bundles) {
                runnable.doBundle(bundle);
            }
        } 
    }

    private ModuleInfo prepareBundle(final ModuleDescriptor md, Application application, final ExtendedDeploymentContext bundleContext)
        throws Exception {

        List<EngineInfo> orderedContainers = null;

        ProgressTracker tracker = new ProgressTracker() {
            public void actOn(Logger logger) {
                for (EngineRef module : get("prepared", EngineRef.class)) {
                    module.clean(bundleContext);
                }

            }

        };

        try {
            // let's get the list of sniffers
            Collection<Sniffer> sniffers = 
                getSniffersForModule(bundleContext, md, application);
            // let's get the list of containers interested in this module
            orderedContainers = deployment.setupContainerInfos(null, sniffers, bundleContext);
        } catch(Exception e) {
            e.printStackTrace();
        }
        return deployment.prepareModule(orderedContainers, md.getArchiveUri(), bundleContext, tracker);
    }

    public ApplicationContainer load(Container container, DeploymentContext context) {

        return new DummyApplication();
    }

    public void unload(ApplicationContainer appContainer, DeploymentContext context) {
        // nothing to do
    }

    public void clean(DeploymentContext context) {
        // nothing to do
    }

    private interface BundleBlock<T> {

        public T doBundle(ModuleDescriptor bundle) throws Exception;
    }
    
    private ExtendedDeploymentContext subContext(final Application application, final DeploymentContext context, final String moduleUri) {

                final ReadableArchive subArchive;
                try {
                    subArchive = context.getSource().getSubArchive(moduleUri);
                    subArchive.setParentArchive(context.getSource());
                } catch(IOException ioe) {
                    ioe.printStackTrace();
                    return null;
                }
                
                final Properties moduleProps = 
                    getModuleProps(context, moduleUri);

                ActionReport subReport = 
                    context.getActionReport().addSubActionsReport();
                return new DeploymentContextImpl(subReport, logger, context.getSource(),
                        context.getCommandParameters(OpsParams.class), env) {

                    @Override
                    public ClassLoader getClassLoader() {
                        try {
                            EarClassLoader appCl = EarClassLoader.class.cast(context.getClassLoader());
                            return appCl.getModuleClassLoader(moduleUri);
                        } catch (ClassCastException e) {
                            return context.getClassLoader();
                        }                        
                    }

                    @Override
                    public ClassLoader getFinalClassLoader() {
                        try {
                            EarClassLoader finalEarCL = (EarClassLoader) context.getFinalClassLoader();
                            return finalEarCL.getModuleClassLoader(moduleUri);
                        } catch (ClassCastException e) {
                            return context.getClassLoader();
                        }
                    } 
                    @Override
                    public ReadableArchive getSource() {
                        return subArchive;
                    }

                    @Override
                    public Properties getAppProps() {
                        return context.getAppProps();
                    }

                    @Override
                    public <U extends OpsParams> U getCommandParameters(Class<U> commandParametersType) {
                        return context.getCommandParameters(commandParametersType);
                    }

                    @Override
                    public void addTransientAppMetaData(String metaDataKey, 
                        Object metaData) {
                        context.addTransientAppMetaData(metaDataKey, 
                            metaData);
                    }

                    @Override
                    public  <T> T getTransientAppMetaData(String metaDataKey, 
                        Class<T> metadataType) {
                        return context.getTransientAppMetaData(metaDataKey, 
                            metadataType);
                    }

                    @Override
                    public Properties getModuleProps() {
                        return moduleProps;
                    }

                    @Override
                    public ReadableArchive getOriginalSource() {
                        try {
                            File appRoot = context.getSourceDir();
                            File origModuleFile = new File(appRoot, moduleUri); 
                            return archiveFactory.openArchive(
                                origModuleFile);
                        } catch (IOException ioe) {
                            return null;
                        }
                    }

                    @Override
                    public File getScratchDir(String subDirName) {
                        String modulePortion = Util.getURIName(
                            getSource().getURI());
                        return (new File(super.getScratchDir(subDirName), 
                            modulePortion));
                    }

                    @Override
                    public <T> T getModuleMetaData(Class<T> metadataType) {
                        try {
                            return metadataType.cast(application.getModuleByUri(moduleUri));
                        } catch (Exception e) {
                            // let's first try the extensions mechanisms...
                            if (RootDeploymentDescriptor.class.isAssignableFrom(metadataType)) {
                                for (RootDeploymentDescriptor extension  : application.getModuleByUri(moduleUri).getExtensionsDescriptors((Class<RootDeploymentDescriptor>) metadataType)) {
                                    // we assume there can only be one type of
                                    if (extension!=null) {
                                        try {
                                            return metadataType.cast(extension);
                                        } catch (Exception e1) {
                                            // next one...
                                        }
                                    }
                                }
                                
                            }

                            return context.getModuleMetaData(metadataType);
                        }
                    }
                };
    }

    private Properties getModuleProps(DeploymentContext context, 
        String moduleUri) {
        Map<String, Properties> modulePropsMap = context.getModulePropsMap();
        Properties moduleProps = modulePropsMap.get(moduleUri);
        if (moduleProps == null) {
            moduleProps = new Properties();
            modulePropsMap.put(moduleUri, moduleProps);
        }
        return moduleProps;
    }


    private String getTypeFromModuleType(XModuleType moduleType) {
        if (moduleType.equals(XModuleType.WAR)) {
            return "web";
        } else if (moduleType.equals(XModuleType.EJB)) {
            return "ejb";
        } else if (moduleType.equals(XModuleType.CAR)) {
            return "appclient";
        } else if (moduleType.equals(XModuleType.RAR)) {
            return "connector";
        }
        return null;
    }

    // get the list of sniffers for sub module and filter out the 
    // incompatible ones
    private Collection<Sniffer> getSniffersForModule(
        DeploymentContext bundleContext, 
        ModuleDescriptor md, Application application) {
        ReadableArchive source = bundleContext.getSource();
        Collection<Sniffer> sniffers = snifferManager.getSniffers(source, bundleContext.getClassLoader());
        String type = getTypeFromModuleType(md.getModuleType());
        Sniffer mainSniffer = null;
        for (Sniffer sniffer : sniffers) {
            if (sniffer.getModuleType().equals(type)) { 
                mainSniffer = sniffer; 
            }
        }

        if (mainSniffer == null) {
            return new ArrayList();
        }

        String [] incompatibleTypes = mainSniffer.getIncompatibleSnifferTypes();
        
        List<Sniffer> sniffersToRemove = new ArrayList<Sniffer>();
        for (Sniffer sniffer : sniffers) {
            for (String incompatType : incompatibleTypes) {
                if (sniffer.getModuleType().equals(incompatType)) {
                    logger.warning(type + " module [" + 
                        md.getArchiveUri() + 
                        "] contains characteristics of other module type: " +
                        incompatType);

                    sniffersToRemove.add(sniffer);
                }
            }
        }

        sniffers.removeAll(sniffersToRemove);

        return sniffers;
    }
}
