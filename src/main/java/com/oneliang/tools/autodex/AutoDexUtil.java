package com.oneliang.tools.autodex;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.oneliang.Constant;
import com.oneliang.thirdparty.asm.util.AsmUtil;
import com.oneliang.thirdparty.asm.util.AsmUtil.FieldProcessor;
import com.oneliang.thirdparty.asm.util.ClassDescription;
import com.oneliang.tools.dex.DexUtil;
import com.oneliang.tools.linearalloc.AllocClassVisitor.MethodReference;
import com.oneliang.tools.linearalloc.LinearAllocUtil;
import com.oneliang.tools.linearalloc.LinearAllocUtil.AllocStat;
import com.oneliang.util.common.JavaXmlUtil;
import com.oneliang.util.common.StringUtil;
import com.oneliang.util.file.FileUtil;

public final class AutoDexUtil {

	public static final int DEFAULT_FIELD_LIMIT=0xFFD0;//dex field must less than 65536,but field stat always less then in
	public static final int DEFAULT_METHOD_LIMIT=0xFFFF;//dex must less than 65536,55000 is more safer then 65535
	public static final int DEFAULT_LINEAR_ALLOC_LIMIT=Integer.MAX_VALUE;
	private static final String CLASSES="classes";
	private static final String DEX="dex";
	private static final String AUTO_DEX_DEX_CLASSES_PREFIX="dexClasses";

	/**
	 * find main dex class list
	 * @return List<String>
	 */
	public static List<String> findMainDexClassList(String androidManifestFullFilename,boolean attachBaseContextMultiDex){
		List<String> mainDexClassList=new ArrayList<String>();
		XPathFactory xPathFactory = XPathFactory.newInstance();
		XPath xPath = xPathFactory.newXPath();
		try{
			Document document = JavaXmlUtil.parse(new FileInputStream(androidManifestFullFilename));
			String applicationName=findApplication(xPath, document);
			if(StringUtil.isNotBlank(applicationName)){
				mainDexClassList.add(applicationName);
			}
			mainDexClassList.addAll(findActivity(xPath, document));
			if(!attachBaseContextMultiDex){
				mainDexClassList.addAll(findProvider(xPath, document));
				mainDexClassList.addAll(findReceiver(xPath, document));
				mainDexClassList.addAll(findService(xPath, document));
			}
		}catch(Exception e){
			throw new AutoDexUtilException(e);
		}
		return mainDexClassList;
	}

	/**
	 * find application
	 * @param xPath
	 * @param document
	 * @return String
	 * @throws Exception
	 */
	private static String findApplication(XPath xPath,Document document) throws Exception{
		String applicationName=null;
		//application
		NodeList nodeList = (NodeList) xPath.evaluate("/manifest/application[@*]", document, XPathConstants.NODESET);
		if(nodeList!=null){
			for(int i=0;i<nodeList.getLength();i++){
				Node node=nodeList.item(i);
				Node nameNode=node.getAttributes().getNamedItem("android:name");
				if(nameNode!=null){
					applicationName=nameNode.getTextContent();
//					System.out.println(applicationName);
				}
			}
		}
		return applicationName;
	}

	/**
	 * find activity
	 * @param xPath
	 * @param document
	 * @return List<String>
	 * @throws Exception
	 */
	private static List<String> findActivity(XPath xPath,Document document) throws Exception{
		List<String> mainActivityList=new ArrayList<String>();
		NodeList nodeList = (NodeList) xPath.evaluate("/manifest/application/activity", document, XPathConstants.NODESET);
		if(nodeList!=null){
			for(int i=0;i<nodeList.getLength();i++){
				Node node=nodeList.item(i);
				String activityName=node.getAttributes().getNamedItem("android:name").getTextContent();
				Node activityExportedNode=node.getAttributes().getNamedItem("android:exported");
				if(activityExportedNode!=null){
					boolean exported=Boolean.parseBoolean(activityExportedNode.getTextContent());
					if(exported){
//						System.out.println(activityName);
						mainActivityList.add(activityName);
					}
				}else{
					Element element=(Element)node;
					NodeList actionNodeList=element.getElementsByTagName("action");
					if(actionNodeList.getLength()>0){
//						System.out.println(activityName);
//						mainActivityList.add(activityName);
					}
					for(int j=0;j<actionNodeList.getLength();j++){
						Node activityActionNode=actionNodeList.item(j).getAttributes().getNamedItem("android:name");
						if(activityActionNode!=null){
							String activityActionName=activityActionNode.getTextContent();
							if(activityActionName.equals("android.intent.action.MAIN")){
//								System.out.println(activityName);
								mainActivityList.add(activityName);
							}
						}
					}
				}
			}
		}
		return mainActivityList;
	}

	/**
	 * find provider
	 * @param xPath
	 * @param document
	 * @return List<String>
	 * @throws Exception
	 */
	private static List<String> findProvider(XPath xPath,Document document) throws Exception{
		List<String> providerList=new ArrayList<String>();
		NodeList nodeList = (NodeList) xPath.evaluate("/manifest/application/provider", document, XPathConstants.NODESET);
		if(nodeList!=null){
			for(int i=0;i<nodeList.getLength();i++){
				Node node=nodeList.item(i);
				Node nameNode=node.getAttributes().getNamedItem("android:name");
				if(nameNode!=null){
//					Node providerExportedNode=node.getAttributes().getNamedItem("android:exported");
//					if(providerExportedNode!=null){
//						boolean exported=Boolean.parseBoolean(providerExportedNode.getTextContent());
//						if(exported){
//							System.out.println(providerName);
							providerList.add(nameNode.getTextContent());
//						}
//					}
				}
			}
		}
		return providerList;
	}

	/**
	 * find receiver
	 * @param xPath
	 * @param document
	 * @return List<String>
	 * @throws Exception
	 */
	private static List<String> findReceiver(XPath xPath,Document document) throws Exception{
		List<String> receiverList=new ArrayList<String>();
		NodeList nodeList = (NodeList) xPath.evaluate("/manifest/application/receiver", document, XPathConstants.NODESET);
		if(nodeList!=null){
			for(int i=0;i<nodeList.getLength();i++){
				Node node=nodeList.item(i);
				Node nameNode=node.getAttributes().getNamedItem("android:name");
				if(nameNode!=null){
					Node receiverExportedNode=node.getAttributes().getNamedItem("android:exported");
					String receiverName=nameNode.getTextContent();
					boolean needToCheckAgain=false;
					if(receiverExportedNode!=null){
						boolean exported=Boolean.parseBoolean(receiverExportedNode.getTextContent());
						if(exported){
//							System.out.println(receiverName);
							receiverList.add(receiverName);
						}else{
							needToCheckAgain=true;
						}
					}else{
						needToCheckAgain=true;
					}
					if(needToCheckAgain){
						Element element=(Element)node;
						NodeList actionNodeList=element.getElementsByTagName("action");
						if(actionNodeList.getLength()>0){
//							System.out.println(receiverName);
							receiverList.add(receiverName);
						}
					}
				}
			}
		}
		return receiverList;
	}

	/**
	 * find service
	 * @param xPath
	 * @param document
	 * @return List<String>
	 * @throws Exception
	 */
	private static List<String> findService(XPath xPath,Document document) throws Exception{
		List<String> serviceList=new ArrayList<String>();
		NodeList nodeList = (NodeList) xPath.evaluate("/manifest/application/service", document, XPathConstants.NODESET);
		if(nodeList!=null){
			for(int i=0;i<nodeList.getLength();i++){
				Node node=nodeList.item(i);
				Node nameNode=node.getAttributes().getNamedItem("android:name");
				if(nameNode!=null){
					String serviceName=nameNode.getTextContent();
					Node serviceExportedNode=node.getAttributes().getNamedItem("android:exported");
					boolean needToCheckAgain=false;
					if(serviceExportedNode!=null){
						boolean exported=Boolean.parseBoolean(serviceExportedNode.getTextContent());
						if(exported){
//							System.out.println(serviceName);
							serviceList.add(serviceName);
						}else{
							needToCheckAgain=true;
						}
					}else{
						needToCheckAgain=true;
					}
					if(needToCheckAgain){
						Element element=(Element)node;
						NodeList actionNodeList=element.getElementsByTagName("action");
						if(actionNodeList.getLength()>0){
//							System.out.println(serviceName);
							serviceList.add(serviceName);
						}
					}
				}
			}
		}
		return serviceList;
	}

	/**
	 * auto dex
	 * @param allClassesJar
	 * @param androidManifestFullFilename
	 * @param attachBaseContext
	 * @param mainDexOtherClassList
	 * @param resourceDirectoryList
	 * @param outputDirectory
	 * @param debug
	 */
	public static void autoDex(String allClassesJar,String androidManifestFullFilename, boolean attachBaseContext, List<String> mainDexOtherClassList, List<String> resourceDirectoryList, String outputDirectory, boolean debug) {
		autoDex(allClassesJar, androidManifestFullFilename, attachBaseContext, mainDexOtherClassList, resourceDirectoryList, outputDirectory, DEFAULT_FIELD_LIMIT, DEFAULT_METHOD_LIMIT, DEFAULT_LINEAR_ALLOC_LIMIT, debug);
	}

	/**
	 * auto dex
	 * @param allClassesJar
	 * @param androidManifestFullFilename
	 * @param attachBaseContext
	 * @param mainDexOtherClassList
	 * @param resourceDirectoryList
	 * @param outputDirectory
	 * @param fieldLimit
	 * @param methodLimit
	 * @param linearAllocLimit
	 * @param debug
	 */
	public static void autoDex(String allClassesJar,String androidManifestFullFilename, boolean attachBaseContext, List<String> mainDexOtherClassList, List<String> resourceDirectoryList, String outputDirectory, final int fieldLimit, final int methodLimit, final int linearAllocLimit, final boolean debug) {
		outputDirectory=new File(outputDirectory).getAbsolutePath();
		FileUtil.createDirectory(outputDirectory);
		long begin=System.currentTimeMillis();
		List<String> classNameList=AutoDexUtil.findMainDexClassList(androidManifestFullFilename,attachBaseContext);
		if(classNameList!=null){
			if(mainDexOtherClassList!=null){
				classNameList.addAll(mainDexOtherClassList);
			}
			final String packageName=parsePackageName(androidManifestFullFilename);
			List<String> mainDexRootClassNameList=new ArrayList<String>();
			for(String className:classNameList){
				className=className.trim();
				if(className.startsWith(Constant.Symbol.DOT)){
					className=packageName+className;
				}
				className=className.replace(Constant.Symbol.DOT, Constant.Symbol.SLASH_LEFT)+Constant.Symbol.DOT+Constant.File.CLASS;
				mainDexRootClassNameList.add(className);
			}
			//find all layout xml
			final Map<String,String> nodeNameMap=new HashMap<String,String>();
			final Map<String,File> allLayoutXmlFileMap=findAllLayoutXmlFileMap(resourceDirectoryList);
			final Map<Integer,Map<String,String>> dexIdClassNameMap=AutoDexUtil.autoDex(allClassesJar, mainDexRootClassNameList, fieldLimit, methodLimit, linearAllocLimit, new FieldProcessor(){
				public void process(String referenceFieldNameWithoutType, ClassDescription classDescription) {
					final String regex="^[\\w/]+R\\$layout\\.([\\w]+)$";
					if(StringUtil.isMatchRegex(referenceFieldNameWithoutType, regex)){
						List<String> regexGroupList=StringUtil.parseRegexGroup(referenceFieldNameWithoutType, regex);
						if(regexGroupList!=null&&!regexGroupList.isEmpty()){
							String filename=regexGroupList.get(0)+Constant.Symbol.DOT+Constant.File.XML;
							if(allLayoutXmlFileMap.containsKey(filename)){
								File layoutXmlFile=allLayoutXmlFileMap.get(filename);
								Queue<String> layoutXmlQueue=new ConcurrentLinkedQueue<String>();
								layoutXmlQueue.add(layoutXmlFile.getAbsolutePath());
								while(!layoutXmlQueue.isEmpty()){
									String fullFilename=layoutXmlQueue.poll();;
									Document document=JavaXmlUtil.parse(fullFilename);
									XPathFactory xPathFactory=XPathFactory.newInstance();
									XPath xPath=xPathFactory.newXPath();
									try{
										NodeList nodeList=(NodeList)xPath.evaluate("//"+Constant.Symbol.WILDCARD, document, XPathConstants.NODESET);
										if(nodeList!=null){
											for(int i=0;i<nodeList.getLength();i++){
												Node node=nodeList.item(i);
												String nodeName=node.getNodeName();
												if(StringUtil.isMatchPattern(nodeName, packageName+Constant.Symbol.WILDCARD)){
													if(!nodeNameMap.containsKey(nodeName)){
														nodeNameMap.put(nodeName, nodeName);
														String className=nodeName.replace(Constant.Symbol.DOT, Constant.Symbol.SLASH_LEFT)+Constant.Symbol.DOT+Constant.File.CLASS;
														if(!classDescription.dependClassNameMap.containsKey(className)){
															classDescription.dependClassNameList.add(className);
															classDescription.dependClassNameMap.put(className,className);
														}
//															System.out.println("in layout class name:"+className);
													}
												}else if(StringUtil.isMatchPattern(nodeName, "include")){
													Node layoutAttribute=node.getAttributes().getNamedItem("layout");
													if(layoutAttribute!=null){
														String includeLayoutXml=layoutAttribute.getTextContent().replace("@layout/", StringUtil.BLANK)+Constant.Symbol.DOT+Constant.File.XML;
														layoutXmlQueue.add(allLayoutXmlFileMap.get(includeLayoutXml).getAbsolutePath());
//															System.out.println("\t"+layoutXmlFile.getAbsolutePath()+",include:"+includeLayoutXml);
													}
												}
											}
										}
									}catch (Exception e) {
										e.printStackTrace();
									}
								}
							}
						}
					}
				}
			});
			System.out.println("Auto dex cost:"+(System.currentTimeMillis()-begin));
			try{
				String splitAndDxTempDirectory=outputDirectory+Constant.Symbol.SLASH_LEFT+"temp";
				final Map<Integer,List<String>> subDexListMap=splitAndDx(allClassesJar, splitAndDxTempDirectory, dexIdClassNameMap, debug);
				//concurrent merge dex
				begin=System.currentTimeMillis();
				final CountDownLatch countDownLatch=new CountDownLatch(subDexListMap.size());
				Set<Integer> dexIdSet=subDexListMap.keySet();
				for(final int dexId:dexIdSet){
					String dexOutputDirectory=outputDirectory;
					String dexFullFilename=null;
					if(dexId==0){
						dexFullFilename=dexOutputDirectory+"/"+CLASSES+Constant.Symbol.DOT+DEX;
					}else{
						dexFullFilename=dexOutputDirectory+"/"+CLASSES+(dexId+1)+Constant.Symbol.DOT+DEX;
					}
					final String finalDexFullFilename=dexFullFilename;
					Thread thread=new Thread(new Runnable(){
						public void run() {
							try{
								DexUtil.androidMergeDex(finalDexFullFilename, subDexListMap.get(dexId));
							}catch(Exception e){
								System.err.println(Constant.Base.EXCEPTION+",dexId:"+dexId+","+e.getMessage());
							}
							countDownLatch.countDown();
						}
					});
					thread.start();
				}
				countDownLatch.await();
				System.out.println("Merge dex cost:"+(System.currentTimeMillis()-begin));
				FileUtil.deleteAllFile(splitAndDxTempDirectory);
			}catch(Exception e){
				throw new AutoDexUtilException(Constant.Base.EXCEPTION, e);
			}
//			ZipFile zipFile = null;
//			try{
//				zipFile=new ZipFile(allClassesJar);
//				FileUtil.createDirectory(outputDirectory);
//				Iterator<Entry<Integer, Map<String, String>>> iterator=dexIdClassNameMap.entrySet().iterator();
//				while(iterator.hasNext()){
//					Entry<Integer, Map<String, String>> entry=iterator.next();
//					int dexId=entry.getKey();
//					final Set<String> classNameSet=entry.getValue().keySet();
//					String classesJar=null;
//					String classNameTxt=null;
//					OutputStream classNameTxtOutputStream=null;
//					try{
//						classNameTxt=outputDirectory+"/"+dexId+Constant.Symbol.DOT+Constant.File.TXT;
//						FileUtil.createFile(classNameTxt);
//						Properties classNameProperties=new Properties();
//						classesJar=outputDirectory+"/"+dexId+Constant.Symbol.DOT+Constant.File.JAR;
//						classesJar=new File(classesJar).getAbsolutePath();
//						FileUtil.createFile(classesJar);
//						ZipOutputStream dexJarOutputStream=new ZipOutputStream(new FileOutputStream(classesJar));
//						for(String className:classNameSet){
//							ZipEntry zipEntry=zipFile.getEntry(className);
//							InputStream inputStream=zipFile.getInputStream(zipEntry);
//							ZipEntry newZipEntry=new ZipEntry(zipEntry.getName());
//							FileUtil.addZipEntry(dexJarOutputStream, newZipEntry, inputStream);
//							classNameProperties.put(className, classesJar);
//						}
//						if(dexJarOutputStream!=null){
//							dexJarOutputStream.flush();
//							dexJarOutputStream.close();
//						}
//						classNameTxtOutputStream=new FileOutputStream(classNameTxt);
//						classNameProperties.store(classNameTxtOutputStream, null);
//					}catch (Exception e) {
//						throw new AutoDexUtilException(classesJar,e);
//					}finally{
//						if(classNameTxtOutputStream!=null){
//							try {
//								classNameTxtOutputStream.flush();
//								classNameTxtOutputStream.close();
//							} catch (Exception e) {
//								throw new AutoDexUtilException(classNameTxt,e);
//							}
//						}
//					}
//				}
//			}catch(Exception e){
//				throw new AutoDexUtilException(e);
//			}finally{
//				if(zipFile!=null){
//					try {
//						zipFile.close();
//					} catch (Exception e) {
//						throw new AutoDexUtilException(e);
//					}
//				}
//			}
		}
	}

	/**
	 * auto dex
	 * @param allClassesJar
	 * @param mainDexRootClassNameList
	 * @param fieldLimit
	 * @param methodLimit
	 * @param linearAllocLimit
	 * @param fieldProcessor
	 * @return Map<Integer, Map<String,String>>, <dexId,classNameMap>
	 */
	public static Map<Integer,Map<String,String>> autoDex(String allClassesJar,List<String> mainDexRootClassNameList, final int fieldLimit, final int methodLimit, final int linearAllocLimit, final FieldProcessor fieldProcessor){
		final Map<Integer,Map<String,String>> dexIdClassNameMap=new HashMap<Integer, Map<String,String>>();
		try{
			if(FileUtil.isExist(allClassesJar)){
				long begin=System.currentTimeMillis();
				//all class description
				Map<String,List<ClassDescription>> referencedClassDescriptionListMap=new HashMap<String,List<ClassDescription>>();
				Map<String,ClassDescription> classDescriptionMap=AsmUtil.findClassDescriptionMapWithJar(allClassesJar,referencedClassDescriptionListMap, fieldProcessor);
				System.out.println("\tclassDescriptionMap:"+classDescriptionMap.size()+",referencedClassDescriptionListMap:"+referencedClassDescriptionListMap.size());
				//all class map
				Map<String,String> allClassNameMap=new HashMap<String,String>();
				Set<String> classNameKeySet=classDescriptionMap.keySet();
				for(String className:classNameKeySet){
					allClassNameMap.put(className, className);
				}
				System.out.println("Find all class description cost:"+(System.currentTimeMillis()-begin));
				//main dex
				begin=System.currentTimeMillis();
				final ZipFile zipFile=new ZipFile(allClassesJar);
				try{
					if(mainDexRootClassNameList!=null){
						begin=System.currentTimeMillis();
						Map<Integer,List<String>> dexClassRootListMap=new HashMap<Integer,List<String>>();
						dexClassRootListMap.put(0, mainDexRootClassNameList);
						Queue<Integer> dexQueue=new ConcurrentLinkedQueue<Integer>();
						dexQueue.add(0);
						final Map<Integer,AllocStat> dexAllocStatMap=new HashMap<Integer,AllocStat>();
						int autoDexId=0;
						while(!dexQueue.isEmpty()){
							Integer dexId=dexQueue.poll();
							List<String> rootClassNameList=dexClassRootListMap.get(dexId);
							Map<String,String> dependClassNameMap=AsmUtil.findAllDependClassNameMap(rootClassNameList, classDescriptionMap, referencedClassDescriptionListMap, allClassNameMap);
							//先算这一垞有多少个方法数和linear
							AllocStat thisTimeAllocStat=new AllocStat();
							thisTimeAllocStat.setMethodReferenceMap(new HashMap<String,String>());
							thisTimeAllocStat.setFieldReferenceMap(new HashMap<String,String>());
							Set<String> keySet=dependClassNameMap.keySet();
							for(String key:keySet){
								AllocStat allocStat=null;
								try {
									allocStat = LinearAllocUtil.estimateClass(zipFile.getInputStream(zipFile.getEntry(key)));
								} catch (FileNotFoundException e) {
									throw new AutoDexUtilException(e);
								}
								thisTimeAllocStat.setTotalAlloc(thisTimeAllocStat.getTotalAlloc()+allocStat.getTotalAlloc());
								List<MethodReference> methodReferenceList=allocStat.getMethodReferenceList();
								if(methodReferenceList!=null){
									for(MethodReference methodReference:methodReferenceList){
										thisTimeAllocStat.getMethodReferenceMap().put(methodReference.toString(), methodReference.toString());
									}
								}
								//field reference map
								ClassDescription classDescription=classDescriptionMap.get(key);
								thisTimeAllocStat.getFieldReferenceMap().putAll(classDescription.referenceFieldNameMap);
								for(String fieldName:classDescription.fieldNameList){
									thisTimeAllocStat.getFieldReferenceMap().put(fieldName, fieldName);
								}
								allClassNameMap.remove(key);
							}
							//然后加上原来dex已经统计的方法数,如果是dexId=0就记一次就好了
							AllocStat dexTotalAllocStat=null;
							if(dexAllocStatMap.containsKey(dexId)){
								dexTotalAllocStat=dexAllocStatMap.get(dexId);
							}else{
								dexTotalAllocStat=new AllocStat();
								dexTotalAllocStat.setMethodReferenceMap(new HashMap<String,String>());
								dexTotalAllocStat.setFieldReferenceMap(new HashMap<String,String>());
								dexAllocStatMap.put(dexId, dexTotalAllocStat);
							}
							//因为dexId=0只会循环一次，所以如果还有类没分完，而且当前是dexId=0的话就开始第二个dex,此注释已过期,现在优先把主dex撑满
							int tempFieldLimit=fieldLimit;
							int tempMethodLimit=methodLimit;
							int tempLinearAllocLimit=linearAllocLimit;
							if(dexId==0){
								int thisTimeFieldLimit=thisTimeAllocStat.getFieldReferenceMap().size();
								int thisTimeMethodLimit=thisTimeAllocStat.getMethodReferenceMap().size();
								int thisTimeTotalAlloc=dexTotalAllocStat.getTotalAlloc()+thisTimeAllocStat.getTotalAlloc();
								if(thisTimeFieldLimit>tempFieldLimit){
									tempFieldLimit=thisTimeFieldLimit;
								}
								if(thisTimeMethodLimit>tempMethodLimit){
									tempMethodLimit=thisTimeMethodLimit;
								}
								if(thisTimeTotalAlloc>tempLinearAllocLimit){
									tempLinearAllocLimit=thisTimeTotalAlloc;
								}
							}
//							if(dexId==0){
//								dexTotalAllocStat.setTotalAlloc(dexTotalAllocStat.getTotalAlloc()+thisTimeAllocStat.getTotalAlloc());
//								dexTotalAllocStat.getMethodReferenceMap().putAll(thisTimeAllocStat.getMethodReferenceMap());
//								//add to current dex class name map
//								if(!dexIdClassNameMap.containsKey(dexId)){
//									dexIdClassNameMap.put(dexId, dependClassNameMap);
//								}
//								//and put the this time alloc stat to dex all stat map
//								dexAllocStatMap.put(dexId, thisTimeAllocStat);
//								autoDexId++;
//							}else{//不是主dex的时候才要考虑合并计算
								//先clone原有的map，然后合并估算一下
								Map<String,String> oldFieldReferenceMap=dexTotalAllocStat.getFieldReferenceMap();
								Map<String,String> oldMethodReferenceMap=dexTotalAllocStat.getMethodReferenceMap();
								Map<String,String> tempFieldReferenceMap=(Map<String,String>)((HashMap<String,String>)oldFieldReferenceMap).clone();
								Map<String,String> tempMethodReferenceMap=(Map<String,String>)((HashMap<String,String>)oldMethodReferenceMap).clone();
								tempFieldReferenceMap.putAll(thisTimeAllocStat.getFieldReferenceMap());
								tempMethodReferenceMap.putAll(thisTimeAllocStat.getMethodReferenceMap());
								int tempTotalAlloc=dexTotalAllocStat.getTotalAlloc()+thisTimeAllocStat.getTotalAlloc();
								//如果没有超过method limit就不增加autoDexId
								if(tempFieldReferenceMap.size()<=tempFieldLimit&&tempMethodReferenceMap.size()<=tempMethodLimit&&tempTotalAlloc<=tempLinearAllocLimit){
									dexTotalAllocStat.setTotalAlloc(dexTotalAllocStat.getTotalAlloc()+thisTimeAllocStat.getTotalAlloc());
									dexTotalAllocStat.getMethodReferenceMap().putAll(thisTimeAllocStat.getMethodReferenceMap());
									dexTotalAllocStat.getFieldReferenceMap().putAll(thisTimeAllocStat.getFieldReferenceMap());
									if(!dexIdClassNameMap.containsKey(dexId)){
										dexIdClassNameMap.put(dexId, dependClassNameMap);
									}else{
										dexIdClassNameMap.get(dexId).putAll(dependClassNameMap);
									}
								}else{
									//this dex is full then next one.
									autoDexId++;
									//add to new dex class name map
									if(!dexIdClassNameMap.containsKey(autoDexId)){
										dexIdClassNameMap.put(autoDexId, dependClassNameMap);
									}
									//and put the this time alloc stat to dex all stat map
									dexAllocStatMap.put(autoDexId, thisTimeAllocStat);
								}
//							}
							//autoDexId不变的时候还要继续当前dex
							Set<String> remainKeySet=allClassNameMap.keySet();
							for(String key:remainKeySet){
								dexQueue.add(autoDexId);
								dexClassRootListMap.put(autoDexId, Arrays.asList(key));
								break;
							}
						}
						System.out.println("Auto split dex cost:"+(System.currentTimeMillis()-begin));
						System.out.println("\tremain classes:"+allClassNameMap.size());
						Iterator<Entry<Integer,AllocStat>> iterator=dexAllocStatMap.entrySet().iterator();
						while(iterator.hasNext()){
							Entry<Integer,AllocStat> entry=iterator.next();
							System.out.println("\tdexId:"+entry.getKey()+"\tlinearAlloc:"+entry.getValue().getTotalAlloc()+"\tfield:"+entry.getValue().getFieldReferenceMap().size()+"\tmethod:"+entry.getValue().getMethodReferenceMap().size());
						}
					}
				}finally{
					zipFile.close();
				}
			}
		}catch(Exception e){
			throw new AutoDexUtilException(e);
		}
		return dexIdClassNameMap;
	}

	/**
	 * split and dx
	 * @param allClassesJar
	 * @param outputDirectory
	 * @param dexIdClassNameMap
	 * @param apkDebug
	 */
	public static Map<Integer,List<String>> splitAndDx(String allClassesJar,final String outputDirectory,final Map<Integer,Map<String,String>> dexIdClassNameMap,final boolean apkDebug){
		final Map<Integer,List<String>> subDexListMap=new HashMap<Integer,List<String>>();
		long begin=System.currentTimeMillis();
		try{
			if(FileUtil.isExist(allClassesJar)){
				final String parentOutputDirectory=new File(outputDirectory).getParent();
				final ZipFile zipFile=new ZipFile(allClassesJar);
				try{
					//copy all classes
					final CountDownLatch splitJarCountDownLatch=new CountDownLatch(dexIdClassNameMap.size());
					Set<Integer> dexIdSet=dexIdClassNameMap.keySet();
					final int fileCountPerJar=500;
					//concurrent split jar
					for(final int dexId:dexIdSet){
						final Set<String> classNameSet=dexIdClassNameMap.get(dexId).keySet();
						Thread thread=new Thread(new Runnable(){
							public void run() {
								int total=classNameSet.size();
								int subDexCount=0,count=0;
								ZipOutputStream dexJarOutputStream=null;
								String classesJar=null;
								String classNameTxt=null;
								String jarSubDexNameTxt=null;
								OutputStream classNameTxtOutputStream=null;
								OutputStream jarSubDexNameTxtOutputStream=null;
								try{
									classNameTxt=parentOutputDirectory+"/"+dexId+Constant.Symbol.DOT+Constant.File.TXT;
									jarSubDexNameTxt=outputDirectory+"/"+dexId+Constant.File.JAR+Constant.Symbol.DOT+Constant.File.TXT;
									Properties classNameProperties=new Properties();
									Properties jarSubDexNameProperties=new Properties();
									for(String className:classNameSet){
										if(count%fileCountPerJar==0){
											classesJar=outputDirectory+"/"+AUTO_DEX_DEX_CLASSES_PREFIX+dexId+Constant.Symbol.UNDERLINE+subDexCount+Constant.Symbol.DOT+Constant.File.JAR;
											classesJar=new File(classesJar).getAbsolutePath();
											FileUtil.createFile(classesJar);
											dexJarOutputStream=new ZipOutputStream(new FileOutputStream(classesJar));
										}
										ZipEntry zipEntry=zipFile.getEntry(className);
										FileUtil.addZipEntry(dexJarOutputStream, zipEntry, zipFile.getInputStream(zipEntry));
										count++;
										classNameProperties.put(className, classesJar);

										if(count%fileCountPerJar==0||count==total){
											if(dexJarOutputStream!=null){
												dexJarOutputStream.flush();
												dexJarOutputStream.close();
											}
											String classesDex=outputDirectory+"/"+AUTO_DEX_DEX_CLASSES_PREFIX+dexId+Constant.Symbol.UNDERLINE+subDexCount+Constant.Symbol.DOT+Constant.File.DEX;
											classesDex=new File(classesDex).getAbsolutePath();
											if(classesJar!=null){
												DexUtil.androidDx(classesDex, Arrays.asList(classesJar), apkDebug);
												if(subDexListMap.containsKey(dexId)){
													subDexListMap.get(dexId).add(classesDex);
												}else{
													List<String> subDexList=new ArrayList<String>();
													subDexList.add(classesDex);
													subDexListMap.put(dexId, subDexList);
												}
											}
											jarSubDexNameProperties.put(classesJar, classesDex);
											subDexCount++;
										}
									}
									classNameTxtOutputStream=new FileOutputStream(classNameTxt);
									classNameProperties.store(classNameTxtOutputStream, null);
									jarSubDexNameTxtOutputStream=new FileOutputStream(jarSubDexNameTxt);
									jarSubDexNameProperties.store(jarSubDexNameTxtOutputStream, null);
								}catch (Exception e) {
									throw new AutoDexUtilException(classesJar,e);
								}finally{
									if(dexJarOutputStream!=null){
										try {
											dexJarOutputStream.flush();
											dexJarOutputStream.close();
										} catch (Exception e) {
											throw new AutoDexUtilException(classesJar,e);
										}
									}
									if(classNameTxtOutputStream!=null){
										try {
											classNameTxtOutputStream.flush();
											classNameTxtOutputStream.close();
										} catch (Exception e) {
											throw new AutoDexUtilException(classNameTxt,e);
										}
									}
									if(jarSubDexNameTxtOutputStream!=null){
										try {
											jarSubDexNameTxtOutputStream.flush();
											jarSubDexNameTxtOutputStream.close();
										} catch (Exception e) {
											throw new AutoDexUtilException(jarSubDexNameTxt,e);
										}
									}
								}
								splitJarCountDownLatch.countDown();
							}
						});
						thread.start();
					}
					splitJarCountDownLatch.await();
					System.out.println("Split multi jar and dx,file count per jar:"+fileCountPerJar+",cost:"+(System.currentTimeMillis()-begin));
				}finally{
					zipFile.close();
				}
			}
		}catch (Exception e) {
			throw new AutoDexUtilException(e);
		}
		return subDexListMap;
	}

	/**
	 * parse package name
	 * @param androidManifestFullFilename
	 * @return String
	 */
	private static String parsePackageName(String androidManifestFullFilename){
		String packageName=null;
		if(FileUtil.isExist(androidManifestFullFilename)){
			XPathFactory factory = XPathFactory.newInstance();
			XPath xpath = factory.newXPath();
			Document document = JavaXmlUtil.parse(androidManifestFullFilename);
			try{
				XPathExpression expression = xpath.compile("/manifest[@package]");
				NodeList nodeList = (NodeList) expression.evaluate(document, XPathConstants.NODESET);
				if(nodeList!=null&&nodeList.getLength()>0){
					Node node=nodeList.item(0);
					packageName=node.getAttributes().getNamedItem("package").getTextContent();
				}
			}catch(Exception e){
				e.printStackTrace();
			}
		}
		return packageName;
	}

	/**
	 * find all layout xml file map
	 * @param resourceDirectoryList
	 * @return Map<String, File>
	 */
	private static Map<String, File> findAllLayoutXmlFileMap(List<String> resourceDirectoryList) {
		Map<String, File> allLayoutXmlFileMap = new HashMap<String, File>();
		if (resourceDirectoryList != null) {
			for (String resourceDirectory : resourceDirectoryList) {
				List<String> layoutXmlFullFilenameList = FileUtil.findMatchFile(resourceDirectory, Constant.Symbol.DOT + Constant.File.XML);
				if (layoutXmlFullFilenameList != null && !layoutXmlFullFilenameList.isEmpty()) {
					for (String layoutXmlFullFilename : layoutXmlFullFilenameList) {
						File layoutXmlFile = new File(layoutXmlFullFilename);
						String filename = layoutXmlFile.getName();
						// filename=filename.substring(0,filename.lastIndexOf(Constant.Symbol.DOT));
						allLayoutXmlFileMap.put(filename, layoutXmlFile);
					}
				}
			}
		}
		return allLayoutXmlFileMap;
	}

	private static class AutoDexUtilException extends RuntimeException{
		private static final long serialVersionUID = -6167451596208904365L;
		public AutoDexUtilException(String message) {
			super(message);
		}

		public AutoDexUtilException(Throwable cause) {
			super(cause);
		}

		public AutoDexUtilException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
