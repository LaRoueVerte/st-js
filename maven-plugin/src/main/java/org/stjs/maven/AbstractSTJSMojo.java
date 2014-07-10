/**
 *  Copyright 2011 Alexandru Craciun, Eyal Kaspi
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.stjs.maven;

import com.google.common.io.Closeables;
import com.google.common.io.Files;
import com.google.debugging.sourcemap.SourceMapFormat;
import com.google.debugging.sourcemap.SourceMapGenerator;
import com.google.debugging.sourcemap.SourceMapGeneratorFactory;
import com.google.debugging.sourcemap.SourceMapSection;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.compiler.util.scan.InclusionScanException;
import org.codehaus.plexus.compiler.util.scan.SourceInclusionScanner;
import org.codehaus.plexus.compiler.util.scan.mapping.SourceMapping;
import org.codehaus.plexus.compiler.util.scan.mapping.SuffixMapping;
import org.codehaus.plexus.util.DirectoryScanner;
import org.jgrapht.DirectedGraph;
import org.jgrapht.alg.CycleDetector;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.traverse.TopologicalOrderIterator;
import org.sonatype.plexus.build.incremental.BuildContext;
import org.stjs.generator.*;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This is the Maven plugin that launches the Javascript generator. The plugin needs a list of packages containing the
 * Java classes that will processed to generate the corresponding Javascript classes. The Javascript files are generated
 * in a configured target folder.
 * 
 * 
 * 
 * @author <a href='mailto:ax.craciun@gmail.com'>Alexandru Craciun</a>
 */
abstract public class AbstractSTJSMojo extends AbstractMojo {
	private static final Logger LOG = Logger.getLogger(AbstractSTJSMojo.class.getName());

	private static final Object PACKAGE_INFO_JAVA = "package-info.java";

	/**
	 * @parameter expression="${project}"
	 * @required
	 * @readonly
	 */
	protected MavenProject project;

	/**
	 * @component
	 */
	protected BuildContext buildContext;
	/**
	 * The list of packages that can be referenced from the classes that will be processed by the generator
	 * 
	 * @parameter
	 */
	protected List<String> allowedPackages;

	/**
	 * A list of inclusion filters for the compiler.
	 * 
	 * @parameter
	 */
	protected Set<String> includes = new HashSet<String>();

	/**
	 * A list of exclusion filters for the compiler.
	 * 
	 * @parameter
	 */
	protected Set<String> excludes = new HashSet<String>();

	/**
	 * Sets the granularity in milliseconds of the last modification date for testing whether a source needs
	 * recompilation.
	 * 
	 * @parameter expression="${lastModGranularityMs}" default-value="0"
	 */
	protected int staleMillis;

	/**
	 * If true the check, if (!array.hasOwnProperty(index)) continue; is added in each "for" array iteration
	 * 
	 * @parameter expression="${generateArrayHasOwnProperty}" default-value="true"
	 */
	protected boolean generateArrayHasOwnProperty;

	/**
	 * If true, it generates for each JavaScript the corresponding source map back to the corresponding Java file. It
	 * also copies the Java source file in the same folder as the generated Javascript file.
	 * 
	 * @parameter expression="${generateSourceMap}" default-value="false"
	 */
	protected boolean generateSourceMap;

	/**
	 * If true, it packs all the generated Javascript file (using the correct dependency order) into a single file named
	 * ${project.artifactName}.js
	 * 
	 * @parameter expression="${pack}" default-value="false"
	 */
	protected boolean pack;

	/**
	 * @parameter expression="${sourceEncoding}" default-value="${project.build.sourceEncoding}"
	 */
	private String sourceEncoding;

	abstract protected List<String> getCompileSourceRoots();

	abstract protected GenerationDirectory getGeneratedSourcesDirectory();

	abstract protected File getBuildOutputDirectory();

	abstract protected List<String> getClasspathElements() throws DependencyResolutionRequiredException;

	private ClassLoader getBuiltProjectClassLoader() throws MojoExecutionException {
		try {
			List<String> runtimeClasspathElements = getClasspathElements();
			URL[] runtimeUrls = new URL[runtimeClasspathElements.size()];
			for (int i = 0; i < runtimeClasspathElements.size(); i++) {
				String element = runtimeClasspathElements.get(i);
				getLog().debug("Classpath:" + element);
				runtimeUrls[i] = new File(element).toURI().toURL();
			}
			return new URLClassLoader(runtimeUrls, Thread.currentThread().getContextClassLoader().getParent());
		} catch (Exception ex) {
			throw new MojoExecutionException("Cannot get builtProjectClassLoader " + ex, ex);
		}
	}

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		GenerationDirectory gendir = getGeneratedSourcesDirectory();

		long t1 = System.currentTimeMillis();
		getLog().info("Generating JavaScript files to " + gendir.getAbsolutePath());

		ClassLoader builtProjectClassLoader = getBuiltProjectClassLoader();

		GeneratorConfigurationBuilder configBuilder = new GeneratorConfigurationBuilder();
		configBuilder.generateArrayHasOwnProperty(generateArrayHasOwnProperty);
		configBuilder.generateSourceMap(generateSourceMap);
		if (sourceEncoding != null) {
			configBuilder.sourceEncoding(sourceEncoding);
		}
		// configBuilder.allowedPackage("org.stjs.javascript");
		configBuilder.allowedPackage("org.junit");
		// configBuilder.allowedPackage("org.stjs.testing");

		if (allowedPackages != null) {
			configBuilder.allowedPackages(allowedPackages);
		}

		// scan all the packages
		for (String sourceRoot : getCompileSourceRoots()) {
			File sourceDir = new File(sourceRoot);
			Collection<String> packages = accumulatePackages(sourceDir);
			configBuilder.allowedPackages(packages);
		}

		GeneratorConfiguration configuration = configBuilder.build();
		Generator generator = new Generator();
		generator.init(builtProjectClassLoader, sourceEncoding);

		int generatedFiles = 0;
		boolean hasFailures = false;
		// scan the modified sources
		for (String sourceRoot : getCompileSourceRoots()) {
			File sourceDir = new File(sourceRoot);
			List<File> sources = new ArrayList<File>();
			SourceMapping mapping = new SuffixMapping(".java", ".js");
			SourceMapping stjsMapping = new SuffixMapping(".java", ".stjs");

			sources = accumulateSources(gendir, sourceDir, mapping, stjsMapping, staleMillis);
			for (File source : sources) {
				if (source.getName().equals(PACKAGE_INFO_JAVA)) {
					getLog().debug("Skipping " + source);
					continue;
				}
				File absoluteSource = new File(sourceDir, source.getPath());
				try {
					File absoluteTarget = (File) mapping.getTargetFiles(gendir.getAbsolutePath(), source.getPath()).iterator().next();
					if (getLog().isDebugEnabled()) {
						getLog().debug("Generating " + absoluteTarget);
					}
					buildContext.removeMessages(absoluteSource);

					if (!absoluteTarget.getParentFile().exists() && !absoluteTarget.getParentFile().mkdirs()) {
						getLog().error("Cannot create output directory:" + absoluteTarget.getParentFile());
						continue;
					}
					String className = getClassNameForSource(source.getPath());
					generator
							.generateJavascript(builtProjectClassLoader, className, sourceDir, gendir, getBuildOutputDirectory(), configuration);
					++generatedFiles;

				} catch (InclusionScanException e) {
					throw new MojoExecutionException("Cannot scan the source directory:" + e, e);
				} catch (MultipleFileGenerationException e) {
					for (JavascriptFileGenerationException jse : e.getExceptions()) {
						buildContext.addMessage(jse.getSourcePosition().getFile(), jse.getSourcePosition().getLine(), jse.getSourcePosition()
								.getColumn(), jse.getMessage(), BuildContext.SEVERITY_ERROR, null);
					}
					hasFailures = true;
					// continue with the next file
				} catch (JavascriptFileGenerationException e) {
					buildContext.addMessage(e.getSourcePosition().getFile(), e.getSourcePosition().getLine(), e.getSourcePosition().getColumn(),
							e.getMessage(), BuildContext.SEVERITY_ERROR, null);
					hasFailures = true;
					// continue with the next file
				} catch (Exception e) {
					// TODO - maybe should filter more here
					buildContext.addMessage(absoluteSource, 1, 1, e.toString(), BuildContext.SEVERITY_ERROR, e);
					hasFailures = true;
					// throw new MojoExecutionException("Error generating javascript:" + e, e);
				}
			}
		}
		generator.close();
		long t2 = System.currentTimeMillis();
		getLog().info("Generated " + generatedFiles + " JavaScript files in " + (t2 - t1) + " ms");
		if (generatedFiles > 0) {
			filesGenerated(generator, gendir);
		}

		if (hasFailures) {
			throw new MojoFailureException("Errors generating JavaScript");
		}
	}

	/**
	 * packs all the files in a single file
	 * 
	 * @param generator
	 * @param gendir
	 * @throws MojoFailureException
	 * @throws MojoExecutionException
	 */
	protected void packFiles(Generator generator, GenerationDirectory gendir) throws MojoFailureException, MojoExecutionException {
		if (!pack) {
			return;
		}
		OutputStream allSourcesFile = null;
		Writer packMapStream = null;
		ClassLoader builtProjectClassLoader = getBuiltProjectClassLoader();
		Map<String, File> currentProjectsFiles = new HashMap<String, File>();

		// pack the files
		try {
			DirectedGraph<String, DefaultEdge> dependencyGraph = new DefaultDirectedGraph<String, DefaultEdge>(DefaultEdge.class);
			File outputFile = new File(gendir.getAbsolutePath(), project.getArtifactId() + ".js");
			allSourcesFile = new BufferedOutputStream(new FileOutputStream(outputFile));
			for (String sourceRoot : getCompileSourceRoots()) {
				File sourceDir = new File(sourceRoot);
				List<File> sources = new ArrayList<File>();
				SourceMapping mapping = new SuffixMapping(".java", ".js");
				SourceMapping stjsMapping = new SuffixMapping(".java", ".stjs");

				// take all the files
				sources = accumulateSources(gendir, sourceDir, mapping, stjsMapping, Integer.MIN_VALUE);
				for (File source : sources) {

					File absoluteTarget = (File) mapping.getTargetFiles(gendir.getAbsolutePath(), source.getPath()).iterator().next();

					String className = getClassNameForSource(source.getPath());
					if (!absoluteTarget.exists()) {
						getLog().debug(className + " is a bridge. Don't add it to the pack file");
						continue;
					}
					// add this file to the hashmap to know that this class is part of the project
					currentProjectsFiles.put(className, absoluteTarget);
					ClassWithJavascript cjs = generator.getExistingStjsClass(builtProjectClassLoader,
							builtProjectClassLoader.loadClass(className));
					dependencyGraph.addVertex(className);
					for (ClassWithJavascript dep : cjs.getDirectDependencies()) {
						if (dep instanceof STJSClass) {
							dependencyGraph.addVertex(dep.getClassName());
							dependencyGraph.addEdge(dep.getClassName(), className);
						}
					}

				}
			}

			// check for cycles
			Set<String> cycles = new CycleDetector<String, DefaultEdge>(dependencyGraph).findCycles();
			if (!cycles.isEmpty()) {
				throw new Exception("Cycles are detected in the dependency graph:\n" + cycles.toString().replace(',', '\n')
						+ "\n Please fix the problem before continuing or disable the packing");
			}
			// dump all the files in the dependency order in the pack file
			List<SourceMapSection> sourceMapSections = new ArrayList<SourceMapSection>();

			int currentLine = 0;
			Iterator<String> it = new TopologicalOrderIterator<String, DefaultEdge>(dependencyGraph);
			while (it.hasNext()) {
				File targetFile = currentProjectsFiles.get(it.next());
				if (targetFile != null) {
					// for this project's files
					if (generateSourceMap) {
						currentLine = appendFileSkipSourceMap(targetFile, outputFile, allSourcesFile, currentLine, sourceMapSections);
					} else {
						Files.copy(targetFile, allSourcesFile);
					}
					allSourcesFile.write('\n');
					allSourcesFile.flush();
				}
			}

			if (generateSourceMap) {
				SourceMapGenerator packSourceMap = SourceMapGeneratorFactory.getInstance(SourceMapFormat.V3);
				File packMapFile = new File(gendir.getAbsolutePath(), project.getArtifactId() + ".map");
				packMapStream = new BufferedWriter(new FileWriter(packMapFile));
				packSourceMap.appendIndexMapTo(packMapStream, outputFile.getName(), sourceMapSections);
			}

		} catch (Exception ex) {
			throw new MojoFailureException("Error when packing files:" + ex.getMessage(), ex);
		} finally {

			try {
				Closeables.close(allSourcesFile, true);
			}
			catch (IOException e) {
				LOG.log(Level.SEVERE, "IOException should not have been thrown.", e);
			}

			try {
				Closeables.close(packMapStream, true);
			}
			catch (IOException e) {
				LOG.log(Level.SEVERE, "IOException should not have been thrown.", e);
			}
		}

	}

	private int appendFileSkipSourceMap(File targetFile, File packFile, OutputStream allSourcesFile, int currentLine,
			List<SourceMapSection> sections) throws IOException {
		List<String> lines = Files.readLines(targetFile, sourceEncoding != null ? Charset.forName(sourceEncoding) : Charset.defaultCharset());
		// remove the @SourceMap stuff
		for (int i = 0; i < lines.size() - 1; ++i) {
			allSourcesFile.write(lines.get(i).getBytes());
			allSourcesFile.write('\n');
		}

		sections.add(SourceMapSection.forMap(getRelativeSourceMapFileName(targetFile, packFile), currentLine, 0));
		return currentLine + lines.size() - 1;
	}

	private String getRelativeSourceMapFileName(File targetFile, File packFile) {
		// remove the common folder name from the target file name
		String relativeName = targetFile.getAbsolutePath().substring(packFile.getParentFile().getAbsolutePath().length() + 1);
		return relativeName.replace(".js", ".map");
	}

	protected void filesGenerated(Generator generator, GenerationDirectory gendir) throws MojoFailureException, MojoExecutionException {
		// copy the javascript support
		try {
			generator.copyJavascriptSupport(getGeneratedSourcesDirectory().getAbsolutePath());
		} catch (Exception ex) {
			throw new MojoFailureException("Error when copying support files:" + ex.getMessage(), ex);
		}

		packFiles(generator, gendir);

	}

	/**
	 * @return the list of Java source files to processed (those which are older than the corresponding Javascript
	 *         file). The returned files are relative to the given source directory.
	 */
	private Collection<String> accumulatePackages(File sourceDir) throws MojoExecutionException {
		final Collection<String> result = new HashSet<String>();
		if (sourceDir == null || !sourceDir.exists()) {
			return result;
		}

		DirectoryScanner ds = new DirectoryScanner();
		ds.setFollowSymlinks(true);
		ds.addDefaultExcludes();
		ds.setBasedir(sourceDir);
		ds.setIncludes(new String[] { "**/*.java" });
		ds.scan();
		for (String fileName : ds.getIncludedFiles()) {
			File file = new File(fileName);
			// Supports classes without packages
			result.add(file.getParent() == null ? "" : file.getParent().replace(File.separatorChar, '.'));
		}

		/*
		 * // Trim root path from file paths for (File file : staleFiles) { String filePath = file.getPath(); String
		 * basePath = sourceDir.getAbsoluteFile().toString(); result.add(new File(filePath.substring(basePath.length() +
		 * 1))); }
		 */
		return result;
	}

	private String getClassNameForSource(String sourcePath) {
		// remove ending .java and replace / by .
		return sourcePath.substring(0, sourcePath.length() - 5).replace(File.separatorChar, '.');
	}

	/**
	 * @return the list of Java source files to processed (those which are older than the corresponding Javascript
	 *         file). The returned files are relative to the given source directory.
	 */
	@SuppressWarnings("unchecked")
	private List<File> accumulateSources(GenerationDirectory gendir, File sourceDir, SourceMapping jsMapping, SourceMapping stjsMapping,
			int stale) throws MojoExecutionException {
		final List<File> result = new ArrayList<File>();
		if (sourceDir == null || !sourceDir.exists()) {
			return result;
		}
		SourceInclusionScanner jsScanner = getSourceInclusionScanner(stale);
		jsScanner.addSourceMapping(jsMapping);

		SourceInclusionScanner stjsScanner = getSourceInclusionScanner(stale);
		stjsScanner.addSourceMapping(stjsMapping);

		final Set<File> staleFiles = new LinkedHashSet<File>();

		for (File f : sourceDir.listFiles()) {
			if (!f.isDirectory()) {
				continue;
			}

			try {
				staleFiles.addAll(jsScanner.getIncludedSources(f.getParentFile(), gendir.getAbsolutePath()));
				staleFiles.addAll(stjsScanner.getIncludedSources(f.getParentFile(), getBuildOutputDirectory()));
			} catch (InclusionScanException e) {
				throw new MojoExecutionException("Error scanning source root: \'" + sourceDir.getPath() + "\' "
						+ "for stale files to recompile.", e);
			}
		}

		// Trim root path from file paths
		for (File file : staleFiles) {
			String filePath = file.getPath();
			String basePath = sourceDir.getAbsoluteFile().toString();
			result.add(new File(filePath.substring(basePath.length() + 1)));
		}
		return result;
	}

	protected SourceInclusionScanner getSourceInclusionScanner(int staleMillis) {
		SourceInclusionScanner scanner;

		if (includes.isEmpty() && excludes.isEmpty()) {
			scanner = new StaleClassSourceScanner(staleMillis, getBuildOutputDirectory());
		} else {
			if (includes.isEmpty()) {
				includes.add("**/*.java");
			}
			scanner = new StaleClassSourceScanner(staleMillis, includes, excludes, getBuildOutputDirectory());
		}

		return scanner;
	}

}
