/*
 * Copyright (c) 2015-2017  Erik Derr [derr@cs.uni-saarland.de]
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package de.infsec.tpl.hashtree;


import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import de.infsec.tpl.hash.AccessFlags;
import de.infsec.tpl.hashtree.comp.clazz.DefaultClassNodeComp;
import de.infsec.tpl.hashtree.comp.clazz.IClassNodeComp;
import de.infsec.tpl.hashtree.comp.method.IMethodNodeComp;
import de.infsec.tpl.hashtree.comp.method.SignatureMethodNodeComp;
import de.infsec.tpl.hashtree.comp.pckg.DefaultPackageNodeComp;
import de.infsec.tpl.hashtree.comp.pckg.IPackageNodeComp;
import de.infsec.tpl.hashtree.node.*;
import de.infsec.tpl.pkg.PackageUtils;
import de.infsec.tpl.utils.Utils;
import de.infsec.tpl.utils.WalaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;



// TODO prune function per node for verboseness?
// TODO serializable via msgpack

// single vs multi-version tree?
// different nodes + multiAnnotation

/*
 * abstract/interface HashTreeOLD
 *
 * props:
 *   - config: hashalgo, single/multi-version
 *
 * common functionality::
 *   - stats
 *   - debug?
 *   - generate?? depends on data
 *   - export (what needs to be stored / serialized (e.g. layers)
 *
 * abstract/interface Node
 *   props::  single vs multi-version nodes
 *
 */


/**
 * TODO
 *
 */
public class HashTree implements Serializable {
	private static final long serialVersionUID = 8890771073564531337L;

	private static final Logger logger = LoggerFactory.getLogger(HashTree.class);


	public static HashFunction hf = Hashing.md5();  // TODO configurable

	AccessFlags accessFlagsFilter = AccessFlags.NO_FLAG;
	boolean verbose = false;  // TODO

	Map<Short,String> id2VersionStr;  // null for single version tree

	private Node rootNode;

//TODO	public void generateMultiVersionTree(IClassHierarchy cha, String version) {};


	public static Hasher getHasher() {
		return hf.newHasher();
	}

	public static Node compNode(Collection<? extends Node> nodes, boolean prune) {
		Hasher hasher = getHasher();
		nodes.stream().sorted(HashUtils.comp).forEach(n -> hasher.putBytes(n.hash));

		Node n = new Node(hasher.hash().asBytes());
		if (!prune)
			n.childs.addAll(nodes);
		return n;
	}




	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof HashTree))
			return false;

		return Arrays.equals(this.getRootHash(), ((HashTree) obj).getRootHash());
	}

	public void generate(IClassHierarchy cha) {
		generate(cha, new DefaultPackageNodeComp(), new DefaultClassNodeComp(), new SignatureMethodNodeComp());
	}


	public void generate(IClassHierarchy cha, IPackageNodeComp pnComp, IClassNodeComp cnComp, IMethodNodeComp mnComp) {
		logger.debug("Generate hash tree..");

		// TODO to be removed
		boolean pruneSubMethod = true;
		boolean pruneMethods = true;
		boolean pruneClasses = false;

		int classHashCount = 0;
		int methodHashCount = 0;

		// create map package name -> set of clazzNodes
		HashMap<String, Set<ClassNode>> packageMap = new HashMap<>();

		for (IClass clazz: cha) {
			if (WalaUtils.isAppClass(clazz)) {

				Collection<IMethod> methods = clazz.getDeclaredMethods();

				// filter methods by access flag
				if (accessFlagsFilter != AccessFlags.NO_FLAG) {
					methods = methods.stream()
						.filter(m -> { int code = AccessFlags.getMethodAccessCode(m);  return code > 0 && (code & accessFlagsFilter.getValue()) == 0x0; })  // if predicate is true, keep in list
						.collect(Collectors.toCollection(ArrayList::new));
				}

				List<MethodNode> methodNodes = methods.stream()
					 .filter(m -> !(m.isBridge() || m.isMethodSynthetic()))  // normalize java|dex bytecode by skipping compiler-generated methods
					 .map(m -> mnComp.comp(m, pruneSubMethod))
					 .sorted(HashUtils.comp)  // sort but do not filter dups
					 .collect(Collectors.toList());

				// normalize - skip classes with no methods
				if (methodNodes.isEmpty()) {
					logger.trace(Utils.INDENT + ">> No methods found for clazz: " + WalaUtils.simpleName(clazz));
					continue;
				}

				// update stats
				methodHashCount += methodNodes.size();
				classHashCount++;

				ClassNode clazzNode = cnComp.comp(methodNodes, clazz, pruneMethods);

				// keep track on classes per package
				String pckgName = PackageUtils.getPackageName(clazz);
				if (!packageMap.containsKey(pckgName)) {
					packageMap.put(pckgName, new TreeSet<>(HashUtils.comp));
				}
				packageMap.get(pckgName).add(clazzNode);
			}
		}


		List<PackageNode> packageNodes = packageMap.keySet().stream()
			.map(p -> pnComp.comp(packageMap.get(p), p, cha, pruneClasses))
			.sorted(HashUtils.comp)
			.collect(Collectors.toList());

		logger.debug(Utils.INDENT + "- generated " + methodHashCount   + " method hashes.");
		logger.debug(Utils.INDENT + "- generated " + classHashCount    + " clazz hashes.");
		logger.debug(Utils.INDENT + "- generated " + packageNodes.size() + " package hashes.");


		// generate root
		rootNode = compNode(packageNodes, false);
		logger.debug(Utils.INDENT + "=> Library Hash: " + HashUtils.hash2Str(rootNode.hash));
	}


	/*
	 * Getter methods
	 */
	public Node getRootNode() {
		return this.rootNode;
	}

	public byte[] getRootHash() {
		return this.rootNode.hash;
	}






	///////////////////  TODO currently required  /////////////

	public static List<PackageNode> toPackageNode(Collection<Node> col) {
		return col.stream()
			.filter(n -> n instanceof PackageNode)
			.map(n -> (PackageNode) n).collect(Collectors.toList());
	}





















	////////////////// TODO to be checked ////////////


	
	public Collection<Node> getPackageNodes() {
		return this.getRootNode().childs;
	}
	
	public int getNumberOfPackages() {
		return rootNode.numberOfChilds();
	}
	
	public int getNumberOfClasses() {
		return rootNode.childs.stream().mapToInt(pn -> pn.childs.size()).sum();
	}
	
	public int getNumberOfClasses(PackageNode pn) {
		return pn.numberOfChilds();
	}
	
	public int getNumberOfMethods(PackageNode pn) {
		return pn.childs.stream().mapToInt(cn -> cn.childs.size()).sum();
	}

	public int getNumberOfMethods() {
		return rootNode.childs.stream()
			.map(pn -> pn.childs)
			.flatMap(Collection::stream)
			.mapToInt(cn -> cn.childs.size()).sum();
	}


}
