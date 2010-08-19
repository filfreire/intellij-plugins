/*
 * Copyright 2010 The authors
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

package com.intellij.struts2.facet;

import com.intellij.facet.ui.FacetBasedFrameworkSupportProvider;
import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.ide.fileTemplates.FileTemplateUtil;
import com.intellij.ide.util.frameworkSupport.FrameworkVersion;
import com.intellij.javaee.model.xml.web.Filter;
import com.intellij.javaee.model.xml.web.FilterMapping;
import com.intellij.javaee.model.xml.web.WebApp;
import com.intellij.javaee.web.facet.WebFacet;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ui.configuration.ModulesConfigurator;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.struts2.StrutsConstants;
import com.intellij.struts2.StrutsFileTemplateGroupDescriptorFactory;
import com.intellij.struts2.facet.ui.StrutsFileSet;
import com.intellij.struts2.facet.ui.StrutsVersion;
import com.intellij.util.text.VersionComparatorUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.event.HyperlinkEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * "Add Framework" support.
 *
 * @author Yann C&eacute;bron
 */
public class StrutsFrameworkSupportProvider extends FacetBasedFrameworkSupportProvider<StrutsFacet> {

  private static final Logger LOG = Logger.getInstance("#com.intellij.struts2.facet.StrutsFrameworkSupportProvider");

  protected StrutsFrameworkSupportProvider() {
    super(StrutsFacetType.INSTANCE);
  }

  public String getTitle() {
    return UIUtil.replaceMnemonicAmpersand("Struts &2");
  }

  @NotNull
  public List<FrameworkVersion> getVersions() {
    final List<FrameworkVersion> result = new ArrayList<FrameworkVersion>();
    for (final StrutsVersion version : StrutsVersion.values()) {
      final String name = version.toString();
      result.add(new FrameworkVersion(name, "struts2-" + name, version.getLibraryInfos()));
    }
    return result;
  }

  protected void setupConfiguration(final StrutsFacet strutsFacet,
                                    final ModifiableRootModel modifiableRootModel, final FrameworkVersion version) {
    final Module module = strutsFacet.getModule();
    StartupManager.getInstance(module.getProject()).runWhenProjectIsInitialized(new Runnable() {
      public void run() {
        final VirtualFile[] sourceRoots = ModuleRootManager.getInstance(module).getSourceRoots();
        if (sourceRoots.length > 0) {
          final PsiDirectory directory = PsiManager.getInstance(module.getProject()).findDirectory(sourceRoots[0]);
          if (directory != null &&
              directory.findFile(StrutsConstants.STRUTS_XML_DEFAULT_FILENAME) == null) {

            final boolean is2_1_X = VersionComparatorUtil.compare(version.getVersionName(), "2.1") > 0;
            final FileTemplateManager fileTemplateManager = FileTemplateManager.getInstance();
            final FileTemplate strutsXmlTemplate =
              fileTemplateManager.getJ2eeTemplate(is2_1_X ?
                                                  StrutsFileTemplateGroupDescriptorFactory.STRUTS_2_1_XML :
                                                  StrutsFileTemplateGroupDescriptorFactory.STRUTS_2_0_XML);

            try {
              final StrutsFacetConfiguration strutsFacetConfiguration = strutsFacet.getConfiguration();

              // create empty struts.xml & fileset
              final PsiElement psiElement = FileTemplateUtil.createFromTemplate(strutsXmlTemplate,
                                                                                StrutsConstants.STRUTS_XML_DEFAULT_FILENAME,
                                                                                null,
                                                                                directory);
              final Set<StrutsFileSet> empty = Collections.emptySet();
              final StrutsFileSet fileSet = new StrutsFileSet(StrutsFileSet.getUniqueId(empty),
                                                              StrutsFileSet.getUniqueName("Default File Set", empty),
                                                              strutsFacetConfiguration);
              fileSet.addFile(((XmlFile) psiElement).getVirtualFile());
              strutsFacetConfiguration.getFileSets().add(fileSet);

              // create filter & mapping in web.xml
              new WriteCommandAction.Simple(modifiableRootModel.getProject()) {
                protected void run() throws Throwable {
                  final WebFacet webFacet = strutsFacet.getWebFacet();
                  final WebApp webApp = webFacet.getRoot();
                  assert webApp != null;

                  final Filter strutsFilter = webApp.addFilter();
                  strutsFilter.getFilterName().setStringValue("struts2");

                  @NonNls final String filterClass;
                  if (is2_1_X) {
                    filterClass = StrutsConstants.STRUTS_2_1_FILTER_CLASS;
                  } else {
                    filterClass = StrutsConstants.STRUTS_2_0_FILTER_CLASS;
                  }
                  strutsFilter.getFilterClass().setStringValue(filterClass);

                  final FilterMapping filterMapping = webApp.addFilterMapping();
                  filterMapping.getFilterName().setValue(strutsFilter);
                  filterMapping.addUrlPattern().setStringValue("/*");
                }
              }.execute();


              final NotificationListener showFacetSettingsListener = new NotificationListener() {
                public void hyperlinkUpdate(@NotNull final Notification notification, @NotNull final HyperlinkEvent event) {
                  if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                    notification.expire();
                    ModulesConfigurator.showFacetSettingsDialog(strutsFacet, null);
                  }
                }
              };

              Notifications.Bus.notify(new Notification("struts2", "Struts 2 Setup",
                                                        "Struts 2 Facet has been created, please <a href=\"more\">setup fileset(s)</a>",
                                                        NotificationType.INFORMATION, showFacetSettingsListener),
                                       module.getProject());

            } catch (Exception e) {
              LOG.error("error creating struts.xml from template", e);
            }
          }
        }
      }
    });
  }

}
