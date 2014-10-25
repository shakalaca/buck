/*
 * Copyright 2013-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.apple.xcode;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import com.facebook.buck.apple.FileExtensions;
import com.facebook.buck.apple.GroupedSource;
import com.facebook.buck.apple.HeaderVisibility;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXHeadersBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXSourcesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configures a PBXProject by adding a PBXNativeTarget and its associated dependencies into a
 * PBXProject object graph.
 */
public class NewNativeTargetProjectMutator {
  private static final Logger LOG = Logger.get(NewNativeTargetProjectMutator.class);

  public static class Result {
    public final PBXNativeTarget target;
    public final PBXGroup targetGroup;

    private Result(PBXNativeTarget target, PBXGroup targetGroup) {
      this.target = target;
      this.targetGroup = targetGroup;
    }
  }

  private final PathRelativizer pathRelativizer;
  private final SourcePathResolver sourcePathResolver;

  private PBXTarget.ProductType productType = PBXTarget.ProductType.BUNDLE;
  private Path productOutputPath = Paths.get("");
  private String productName = "";
  private String targetName;
  private Optional<String> gid = Optional.absent();
  private Iterable<GroupedSource> sources = ImmutableList.of();
  private ImmutableMap<SourcePath, String> sourceFlags = ImmutableMap.of();
  private boolean shouldGenerateCopyHeadersPhase = true;

  public NewNativeTargetProjectMutator(
      PathRelativizer pathRelativizer,
      SourcePathResolver sourcePathResolver,
      BuildTarget buildTarget) {
    this.pathRelativizer = pathRelativizer;
    this.sourcePathResolver = sourcePathResolver;
    this.targetName = buildTarget.getFullyQualifiedName();
  }

  /**
   * Set product related configuration.
   *
   * @param productType       declared product type
   * @param productName       product display name
   * @param productOutputPath build output relative product path.
   */
  public NewNativeTargetProjectMutator setProduct(
      PBXNativeTarget.ProductType productType,
      String productName,
      Path productOutputPath) {
    this.productName = productName;
    this.productType = productType;
    this.productOutputPath = productOutputPath;
    return this;
  }

  public NewNativeTargetProjectMutator setGid(Optional<String> gid) {
    this.gid = gid;
    return this;
  }

  public NewNativeTargetProjectMutator setTargetName(String targetName) {
    this.targetName = targetName;
    return this;
  }

  public NewNativeTargetProjectMutator setSources(
      Iterable<GroupedSource> sources,
      ImmutableMap<SourcePath, String> sourceFlags) {
    this.sources = sources;
    this.sourceFlags = sourceFlags;
    return this;
  }

  public NewNativeTargetProjectMutator setShouldGenerateCopyHeadersPhase(boolean value) {
    this.shouldGenerateCopyHeadersPhase = value;
    return this;
  }


  public Result buildTargetAndAddToProject(PBXProject project) {
    PBXNativeTarget target = new PBXNativeTarget(targetName, productType);
    PBXGroup targetGroup = project.getMainGroup().getOrCreateChildGroupByName(targetName);

    if (gid.isPresent()) {
      target.setGlobalID(gid.get());
    }

    // Phases
    addPhasesAndGroupsForSources(target, targetGroup);

    // Product

    PBXGroup productsGroup = project.getMainGroup().getOrCreateChildGroupByName("Products");
    PBXFileReference productReference = productsGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(PBXReference.SourceTree.BUILT_PRODUCTS_DIR, productOutputPath));
    target.setProductName(productName);
    target.setProductReference(productReference);

    project.getTargets().add(target);
    return new Result(target, targetGroup);
  }

  private void addPhasesAndGroupsForSources(PBXNativeTarget target, PBXGroup targetGroup) {
    PBXGroup sourcesGroup = targetGroup.getOrCreateChildGroupByName("Sources");
    // Sources groups stay in the order in which they're declared in the BUCK file.
    sourcesGroup.setSortPolicy(PBXGroup.SortPolicy.UNSORTED);
    PBXSourcesBuildPhase sourcesBuildPhase = new PBXSourcesBuildPhase();
    PBXHeadersBuildPhase headersBuildPhase = new PBXHeadersBuildPhase();

    traverseGroupsTreeAndHandleSources(
        sourcesGroup,
        sourcesBuildPhase,
        // We still want to create groups for header files even if header build phases
        // are replaced with header maps.
        !shouldGenerateCopyHeadersPhase
            ? Optional.<PBXHeadersBuildPhase>absent()
            : Optional.of(headersBuildPhase),
        sources,
        sourceFlags);

    if (!sourcesBuildPhase.getFiles().isEmpty()) {
      target.getBuildPhases().add(sourcesBuildPhase);
    }
    if (!headersBuildPhase.getFiles().isEmpty()) {
      target.getBuildPhases().add(headersBuildPhase);
    }
  }

  private void traverseGroupsTreeAndHandleSources(
      PBXGroup sourcesGroup,
      PBXSourcesBuildPhase sourcesBuildPhase,
      Optional<PBXHeadersBuildPhase> headersBuildPhase,
      Iterable<GroupedSource> groupedSources,
      ImmutableMap<SourcePath, String> sourceFlags) {
    for (GroupedSource groupedSource : groupedSources) {
      switch (groupedSource.getType()) {
        case SOURCE_PATH:
          if (sourcePathResolver.isSourcePathExtensionInSet(
              groupedSource.getSourcePath(),
              FileExtensions.CLANG_HEADERS)) {
            addSourcePathToHeadersBuildPhase(
                groupedSource.getSourcePath(),
                sourcesGroup,
                headersBuildPhase,
                sourceFlags);
          } else {
            addSourcePathToSourcesBuildPhase(
                groupedSource.getSourcePath(),
                sourcesGroup,
                sourcesBuildPhase,
                sourceFlags);
          }
          break;
        case SOURCE_GROUP:
          PBXGroup newSourceGroup = sourcesGroup.getOrCreateChildGroupByName(
              groupedSource.getSourceGroupName());
          // Sources groups stay in the order in which they're declared in the BUCK file.
          newSourceGroup.setSortPolicy(PBXGroup.SortPolicy.UNSORTED);
          traverseGroupsTreeAndHandleSources(
              newSourceGroup,
              sourcesBuildPhase,
              headersBuildPhase,
              groupedSource.getSourceGroup(),
              sourceFlags);
          break;
        default:
          throw new RuntimeException("Unhandled grouped source type: " + groupedSource.getType());
      }
    }
  }

  private void addSourcePathToSourcesBuildPhase(
      SourcePath sourcePath,
      PBXGroup sourcesGroup,
      PBXSourcesBuildPhase sourcesBuildPhase,
      ImmutableMap<SourcePath, String> sourceFlags) {
    PBXFileReference fileReference = sourcesGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputDirToRootRelative(sourcePathResolver.getPath(sourcePath))));
    PBXBuildFile buildFile = new PBXBuildFile(fileReference);
    sourcesBuildPhase.getFiles().add(buildFile);
    String customFlags = sourceFlags.get(sourcePath);
    if (customFlags != null) {
      NSDictionary settings = new NSDictionary();
      settings.put("COMPILER_FLAGS", customFlags);
      buildFile.setSettings(Optional.of(settings));
    }
    LOG.verbose(
        "Added source path %s to group %s, flags %s, PBXFileReference %s",
        sourcePath,
        sourcesGroup.getName(),
        customFlags,
        fileReference);
  }

  private void addSourcePathToHeadersBuildPhase(
      SourcePath headerPath,
      PBXGroup headersGroup,
      Optional<PBXHeadersBuildPhase> headersBuildPhase,
      ImmutableMap<SourcePath, String> sourceFlags) {
    PBXFileReference fileReference = headersGroup.getOrCreateFileReferenceBySourceTreePath(
        new SourceTreePath(
            PBXReference.SourceTree.SOURCE_ROOT,
            pathRelativizer.outputPathToSourcePath(headerPath)));
    PBXBuildFile buildFile = new PBXBuildFile(fileReference);
    String headerFlags = sourceFlags.get(headerPath);
    if (headerFlags != null) {
      // If we specify nothing, Xcode will use "project" visibility.
      NSDictionary settings = new NSDictionary();
      settings.put(
          "ATTRIBUTES",
          new NSArray(new NSString(HeaderVisibility.fromString(headerFlags).toXcodeAttribute())));
      buildFile.setSettings(Optional.of(settings));
    } else {
      buildFile.setSettings(Optional.<NSDictionary>absent());
    }
    if (headersBuildPhase.isPresent()) {
      headersBuildPhase.get().getFiles().add(buildFile);
      LOG.verbose(
          "Added header path %s to headers group %s, flags %s, PBXFileReference %s",
          headerPath,
          headersGroup.getName(),
          headerFlags,
          fileReference);
    } else {
      LOG.verbose(
          "Skipped header path %s to headers group %s, flags %s, PBXFileReference %s",
          headerPath,
          headersGroup.getName(),
          headerFlags,
          fileReference);
    }
  }
}
