/*******************************************************************************
 * Copyright 2013 Johns Hopkins University
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package edu.jhu.pha.vospace.storage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import org.apache.commons.configuration.Configuration;
import org.apache.http.HttpException;
import org.apache.http.client.HttpClient;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.PoolingClientConnectionManager;
import org.apache.log4j.Logger;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParseException;

import com.rackspacecloud.client.cloudfiles.FilesAccountInfo;
import com.rackspacecloud.client.cloudfiles.FilesAuthorizationException;
import com.rackspacecloud.client.cloudfiles.FilesClient;
import com.rackspacecloud.client.cloudfiles.FilesContainerInfo;
import com.rackspacecloud.client.cloudfiles.FilesException;
import com.rackspacecloud.client.cloudfiles.FilesInvalidNameException;
import com.rackspacecloud.client.cloudfiles.FilesNotFoundException;
import com.rackspacecloud.client.cloudfiles.FilesObject;
import com.rackspacecloud.client.cloudfiles.FilesObjectMetaData;

import edu.jhu.pha.vospace.DbPoolServlet;
import edu.jhu.pha.vospace.DbPoolServlet.SqlWorker;
import edu.jhu.pha.vospace.SettingsServlet;
import edu.jhu.pha.vospace.api.exceptions.BadRequestException;
import edu.jhu.pha.vospace.api.exceptions.InternalServerErrorException;
import edu.jhu.pha.vospace.api.exceptions.NotFoundException;
import edu.jhu.pha.vospace.api.exceptions.PermissionDeniedException;
import edu.jhu.pha.vospace.node.NodeInfo;
import edu.jhu.pha.vospace.node.NodePath;
import edu.jhu.pha.vospace.oauth.UserHelper;

public class SwiftStorageManager implements StorageManager {

	private FilesClient cli;
	private final static Configuration conf = SettingsServlet.getConfig();
	private static final Logger logger = Logger.getLogger(SwiftStorageManager.class);
	
	private SwiftJsonCredentials credentials;
	
	//private final String username;
	
	private final int CONNECT_TIMEOUT = 600000;
	
	private final String AUTH_TOKEN = "29212414e78145e7a1d1b3cf7ddaa2ef";
	private final String storageURL = "http://zinc26.pha.jhu.edu:8081/v1/AUTH_24b79d0aadf04c9eb19dd9aeb5706caa";
	
	
	//HTTP client
	private static PoolingClientConnectionManager cm = null;
	private static HttpClient httpClient = null;
	
	public static String generateRandomCredentials(final String username) {
        return DbPoolServlet.goSql("Generate random credentials",
        		"select username, apikey from storage_users_pool where user_id IS NULL limit 1;",
                new SqlWorker<String>() {
                    @Override
                    public String go(Connection conn, PreparedStatement stmt) throws SQLException {
                        ResultSet rs = stmt.executeQuery();
                        if (rs.next()) {
            				String user = rs.getString("username");
            				String password = rs.getString("apikey");
            				PreparedStatement prep = conn.prepareStatement("update storage_users_pool SET user_id = (select user_id from user_identities where identity = ?) where username = ?");
            				prep.setString(1, username);
            				prep.setString(2, user);
            				prep.execute();

            				logger.debug(username+" "+user);
            				
            				StringWriter writer = new StringWriter();
            		    	JsonFactory f = new JsonFactory();
            		    	try {
            		        	JsonGenerator g = f.createJsonGenerator(writer);
            		        	 
            		        	g.writeStartObject();
            		        	g.writeStringField("username", user);
            		        	g.writeStringField("apikey", password);
            		        	g.writeEndObject();

            		        	g.close(); // important: will force flushing of output, close underlying output stream

            		        	return writer.getBuffer().toString();
            		    	} catch(JsonGenerationException ex) {
            					throw new InternalServerErrorException("Error generating user storage credentials. "+ex.getMessage());
            		    	} catch (IOException e) {
            					throw new InternalServerErrorException("Error generating user storage credentials. "+e.getMessage());
            				}
                        
                        } else
            				throw new PermissionDeniedException("The user does not exist.");
                    }
                }
        );
		
	}

	public static HttpClient getHttpClient() {
		if(null == cm) {
		
			SchemeRegistry schemeRegistry = new SchemeRegistry();
			schemeRegistry.register(
			         new Scheme("http", 80, PlainSocketFactory.getSocketFactory()));
			schemeRegistry.register(
			         new Scheme("https", 443, SSLSocketFactory.getSocketFactory()));
	
			cm = new PoolingClientConnectionManager(schemeRegistry);
			// Increase max total connection to 200
			cm.setMaxTotal(200);
			// Increase default max connection per route to 20
			cm.setDefaultMaxPerRoute(50);
		}
		 
		if(null == httpClient)
			httpClient = new DefaultHttpClient(cm);
		
		return httpClient;
	}
	
	/**
	 * Default constructor
	 */
	/*public SwiftStorageManager(String username) {
		this.username = username;
		this.credentials = UserHelper.getDataStoreCredentials(username);

		try {
			cli = new FilesClient(getHttpClient(), credentials.getUsername(),credentials.getApikey(),conf.getString("storage.url"), null, CONNECT_TIMEOUT);
			
			if(null != credentials.getStorageurl() && null != credentials.getAuthtoken()){
				cli.login(credentials.getAuthtoken(), credentials.getStorageurl(), null);
			} else {
				cli.login();
				credentials.setStorageurl(cli.getStorageURL());
				credentials.setAuthtoken(cli.getAuthToken());
				logger.debug("Added token to DB");
				UserHelper.setDataStoreCredentials(username, credentials.toString());
			}
				
		} catch (JsonParseException ex) {
			ex.printStackTrace();
			throw new InternalServerErrorException(ex);
		} catch (IOException e) {
			e.printStackTrace();
			throw new InternalServerErrorException(e, "Error reading credentials from db.");
		} catch (HttpException ex) {
			ex.printStackTrace();
			throw new InternalServerErrorException(ex);
		}

	
	}*/




	public SwiftStorageManager() {
		cli = new FilesClient(getHttpClient(), AUTH_TOKEN, CONNECT_TIMEOUT);
	}

	/**
	 * 
	 * Copy the bytes from the specified old location to the specified new location
	 * in the current backend storage
	 * @param oldLocationId The old location of the bytes
	 * @param newLocationId The new location of the bytes
	 */
	@Override
	public void copyBytes(NodePath oldNodePath, NodePath newNodePath, boolean keepBytes) {
		try {
			FilesObjectMetaData meta = getClient().getKsObjectMetaData(oldNodePath.getContainerName(), oldNodePath.getNodeRelativeStoragePath());
			try {
				// if != null then exists manifest for chunked upload
				if(null != meta.getManifestPrefix() && keepBytes){
					throw new BadRequestException("Copying files with segments is not supported.");
				}
				getClient().copyKsObject(oldNodePath.getContainerName(), oldNodePath.getNodeRelativeStoragePath(), newNodePath.getContainerName(), newNodePath.getNodeRelativeStoragePath());
				
				if(!keepBytes)
					getClient().deleteKsObject(oldNodePath.getContainerName(), oldNodePath.getNodeRelativeStoragePath());
			} catch (FilesInvalidNameException e) {
				throw new NotFoundException("Node Not Found");
			} catch (HttpException e) {
				throw new BadRequestException(e);
			} catch (IOException e) {
				throw new InternalServerErrorException(e.getMessage());
			}
		} catch(FilesNotFoundException ex) {
			// file does not exist: just exit (can be just empty file in metadata)
		} catch(Exception ex) {
			throw new InternalServerErrorException(ex.getMessage());
		}
		
		//updateCredentials();
	}
	
	/**
	 * Create a container at the specified location in the current backend storage
	 * @param locationId The location of the container
	 */
	@Override
	public void createContainer(NodePath npath) {
		try {
			if(npath.getNodeRelativeStoragePath().isEmpty()) {// creating first level container (bucket)
				if(!npath.getContainerName().isEmpty() /*is empty when creating the root node for new user */ && 
						!getClient().containerExists(npath.getContainerName())){
					logger.debug("Creating container "+npath.getContainerName());
					getClient().createKsContainer(npath.getContainerName());
				}
			}
    		//updateCredentials();

		} catch (FilesException ex) {
			throw new InternalServerErrorException(ex);
		} catch (IOException ex) {
			throw new InternalServerErrorException(ex);
		} catch (HttpException ex) {
			throw new InternalServerErrorException(ex);
		}
	}

	/**
	 * Get the bytes from the specified location in the current backend storage
	 * @param locationId The location of the bytes
	 * @return a stream containing the requested bytes
	 */
	@Override
	public InputStream getBytes(NodePath npath) {
		try {
			InputStream inp = getClient().getKsObjectAsStream(npath.getContainerName(), npath.getNodeRelativeStoragePath());
			//updateCredentials();
			return inp;
		} catch (FilesAuthorizationException e) {
			throw new InternalServerErrorException(e);
		} catch (FilesInvalidNameException e) {
			throw new InternalServerErrorException(e);
		} catch (FilesNotFoundException e) {
			/* Return empty stream */
			return new ByteArrayInputStream(new byte[]{});
			//throw new NotFoundException("Node Not Found");
		} catch (HttpException e) {
			throw new InternalServerErrorException(e);
		} catch (IOException e) {
			throw new InternalServerErrorException(e);
		}
	}

	@Override
	public long getBytesUsed() {
		try {
			long bytesUsed = getClient().getKsAccountInfo().getBytesUsed();
			//updateCredentials();
			return bytesUsed;
		} catch (FilesAuthorizationException e) {
			throw new InternalServerErrorException(e);
		} catch (FilesException e) {
			throw new InternalServerErrorException(e);
		} catch (IOException e) {
			throw new InternalServerErrorException(e);
		} catch (HttpException e) {
			throw new InternalServerErrorException(e);
		}
	}

	/**
	 * @return OpenStack connector
	 */
	private FilesClient getClient() {
		return cli;
	}

	/*
	 * (non-Javadoc)
	 * @see edu.jhu.pha.vospace.storage.StorageManager#getStorageUrl()
	 */
	@Override
	public String getStorageUrl() {
			return getClient().getStorageURL();
	}
	
	/*
	 * (non-Javadoc)
	 * @see edu.jhu.pha.vospace.storage.StorageManager#getNodeSyncAddress(java.lang.String)
	 */
	@Override
	public String getNodeSyncAddress(String container) {
		try {
			return getClient().getKsContainerInfo(container).getSyncTo();
		} catch (FilesAuthorizationException e) {
			throw new InternalServerErrorException(e);
		} catch (FilesException e) {
			throw new InternalServerErrorException(e);
		} catch (IOException e) {
			throw new InternalServerErrorException(e);
		} catch (HttpException e) {
			throw new InternalServerErrorException(e);
		}
	}

	/**
	 * Put the bytes from the specified input stream at the specified location in 
	 * the current backend storage
	 * @param location The location for the bytes
	 * @param stream The stream containing the bytes
	 * @param size The stream size
	 */
	@Override
	public void putBytes(NodePath npath, InputStream stream) {
		try {
			getClient().storeKsStreamedObject(npath.getContainerName(), stream, "application/file", npath.getNodeRelativeStoragePath(), new Hashtable<String, String>());
		} catch (HttpException e) {
			throw new InternalServerErrorException(e);
		} catch (IOException e) {
			throw new InternalServerErrorException(e);
		}
		//updateCredentials();
	}

	/**
	 * Remove the bytes at the specified location in the current backend storage
	 * @param locationId The location of the bytes
	 */
	@Override
	public void remove(NodePath npath, boolean removeChunks) {
		
		final int PAGE_SIZE = 1000;
		
		try {
			if (npath.getNodeRelativeStoragePath().isEmpty()) {
				List<FilesObject> contContent = getClient().listKsObjects(npath.getContainerName(), PAGE_SIZE);
				while(!contContent.isEmpty()) {
					for(FilesObject obj: contContent) {
						try { 
							if(removeChunks) removeObjectSegments(npath.getContainerName(), obj.getName());
							getClient().deleteKsObject(npath.getContainerName(), obj.getName()); 
						} catch (Exception e) {}
					}
					contContent = getClient().listKsObjects(npath.getContainerName(), PAGE_SIZE);
				}
				try { getClient().deleteKsContainer(npath.getContainerName()); } catch (Exception e) {}
			} else {
				//List<FilesObject> contContent = getClient().listObjects(npath.getContainerName(), npath.getNodeRelativeStoragePath(), 100);
				List<FilesObject> contContent = getClient().listKsObjectsStartingWith(npath.getContainerName(), npath.getNodeRelativeStoragePath()+"/", null, PAGE_SIZE, null,null, null);
				while(!contContent.isEmpty()) {
					for(FilesObject obj: contContent) {
						try {
							if(removeChunks) removeObjectSegments(npath.getContainerName(), obj.getName());
							getClient().deleteKsObject(npath.getContainerName(), obj.getName()); 
						} catch (Exception e) {}
					}
					//contContent = getClient().listObjects(npath.getContainerName(), npath.getNodeRelativeStoragePath(), 100);
					contContent = getClient().listKsObjectsStartingWith(npath.getContainerName(), npath.getNodeRelativeStoragePath()+"/", null, PAGE_SIZE, null,null,null);
				}
				try {
					if(removeChunks) removeObjectSegments(npath.getContainerName(), npath.getNodeRelativeStoragePath());
					getClient().deleteKsObject(npath.getContainerName(), npath.getNodeRelativeStoragePath()); 
				} catch (Exception e) {}
			}
		} catch(FilesNotFoundException e) {
			//ignore
		} catch (FilesException e) {
			throw new InternalServerErrorException(e);
		} catch (HttpException e) {
			throw new InternalServerErrorException(e);
		} catch (IOException e) {
			throw new InternalServerErrorException(e);
		}
		//updateCredentials();
	}

	/**
	 * Removes existing object's segments
	 * @param containerName
	 * @param objectName
	 * @throws IOException
	 * @throws FilesNotFoundException
	 * @throws HttpException
	 * @throws FilesAuthorizationException
	 * @throws FilesInvalidNameException
	 * @throws FilesException
	 */
	void removeObjectSegments(String containerName, String objectName)
			throws IOException, FilesNotFoundException, HttpException,
			FilesAuthorizationException, FilesInvalidNameException,
			FilesException {
		FilesObjectMetaData meta = getClient().getKsObjectMetaData(containerName, objectName);
		// if != null then exists manifest for chunked upload
		if(null != meta.getManifestPrefix()){
			NodePath path = new NodePath(meta.getManifestPrefix());
			List<FilesObject> segmList = getClient().listKsObjects(path.getContainerName(), path.getNodeRelativeStoragePath(), '/');
			for(FilesObject segm: segmList) {
				getClient().deleteKsObject(conf.getString("chunked_container"), segm.getName());
				logger.debug("Deleted segm "+segm.getName());
			}
		}
	}

	@Override
	public void removeObjectSegment(String chunkedId) {
		try {
			List<FilesObject> segmList = getClient().listKsObjects(conf.getString("chunked_container"), chunkedId, '/');
			for(FilesObject segm: segmList) {
				try {
					getClient().deleteKsObject(conf.getString("chunked_container"), segm.getName());
				} catch (Exception ignored) {}
				logger.debug("Deleted segm "+segm.getName());
			}
		} catch(FilesException e) {
			throw new InternalServerErrorException(e);
		} catch (IOException e) {
			throw new InternalServerErrorException(e);
		} catch (HttpException e) {
			throw new InternalServerErrorException(e);
		}
	}


	@Override
	public void setNodeSyncTo(String container, String syncTo, String syncKey) {
		try {
			getClient().setKsSyncTo(container, syncTo, syncKey);
		} catch (FilesAuthorizationException e) {
			throw new InternalServerErrorException(e);
		} catch (FilesException e) {
			throw new InternalServerErrorException(e);
		} catch (IOException e) {
			throw new InternalServerErrorException(e);
		} catch (HttpException e) {
			throw new InternalServerErrorException(e);
		}
	}

	/*private void //updateCredentials() {
		if(!(cli.getAuthToken().equals(credentials.getAuthtoken()) && cli.getStorageURL().equals(credentials.getStorageurl()))) {
			credentials.setStorageurl(cli.getStorageURL());
			credentials.setAuthtoken(cli.getAuthToken());
			logger.debug("Added token to DB");
			UserHelper.setDataStoreCredentials(username, credentials.toString());
		}
	}*/

	@Override
	public void updateNodeInfo(NodePath npath, NodeInfo nodeInfo) {
		try {
			if(npath.isRoot(false)) { // root node
		    	FilesAccountInfo accountInfo = getClient().getKsAccountInfo();
				nodeInfo.setSize(accountInfo.getBytesUsed());
				nodeInfo.setContentType("application/directory");
	    	} else if(npath.getNodeStoragePathArray().length == 1) { // container info
				if(!getClient().containerExists(npath.getContainerName()))
					return;
				
				FilesContainerInfo contInfo = getClient().getKsContainerInfo(npath.getContainerName());
				nodeInfo.setSize(contInfo.getTotalSize());
				nodeInfo.setContentType("application/directory");
	    	} else { // info for a node inside a container
	    		try {
			    	FilesObjectMetaData nodeMeta = getClient().getKsObjectMetaData(npath.getContainerName(), npath.getNodeRelativeStoragePath());
			    	nodeInfo.setSize(Long.parseLong(nodeMeta.getContentLength()));
			    	nodeInfo.setContentType(nodeMeta.getMimeType());
	    		} catch(FilesNotFoundException e) {
			    	nodeInfo.setSize(0);
			    	nodeInfo.setContentType("application/file");
			    	logger.debug("Info for non-existent object");
	    		}
	    	}
		} catch(FilesException ex) {
			logger.error("Not found file "+npath.getNodeStoragePath());
		} catch (IOException ex) {
			ex.printStackTrace();
			throw new InternalServerErrorException(ex);
		} catch (HttpException ex) {
			ex.printStackTrace();
			throw new InternalServerErrorException(ex);
		}
		////updateCredentials();
	}

	/**
	 * Creates the manifest that combines the chunks into single file.
	 * If the file already exists and points to other chunks, these will be removed.
	 */
	@Override
	public void putChunkedBytes(NodePath nodePath, String chunkedId) {
		try {
			String manifest = conf.getString("chunked_container")+"/"+chunkedId;
			try {
				removeObjectSegments(nodePath.getContainerName(), nodePath.getNodeRelativeStoragePath());
				getClient().updateKsObjectManifest(nodePath.getContainerName(), nodePath.getNodeRelativeStoragePath(), manifest);
			} catch(FilesNotFoundException ex) {
				getClient().createKsManifestObject(nodePath.getContainerName(), "application/file", nodePath.getNodeRelativeStoragePath(), manifest, new Hashtable<String, String>());
			}
		} catch (HttpException e) {
			throw new InternalServerErrorException(e);
		} catch (IOException e) {
			throw new InternalServerErrorException(e);
		}
		////updateCredentials();
	}

	public static void main(String[] s) throws IOException, HttpException {

		SwiftStorageManager ssm = new SwiftStorageManager();
		
				//Create Container
				/*NodePath n1 = new NodePath("L");
				ssm.createContainer(n1);*/
				
				//Account Info
				//System.out.println(ssm.getBytesUsed());
				
				//Container Info
				//System.out.println(ssm.getNodeSyncAddress("first_cont"));
				
				//Get Object Data
				
				/*NodePath n = new NodePath("Test1/Testobj");
				SwiftStorageManager ssm = new SwiftStorageManager();
				InputStream is = ssm.getBytes(n);
				System.out.println(is.read());*/
				
				//Store Object
				/*NodePath n = new NodePath("L/Newobj");
				File f = new File("C:\\Users\\pinkyanup\\Desktop\\Test file.docx");
				InputStream b = new FileInputStream(f);
				ssm.putBytes(n, b);*/
				
				//Copy Object
				/*NodePath n1 = new NodePath("Test1/Testobj");
				NodePath n2 = new NodePath("S/Movedobj");
				ssm.copyBytes(n1, n2, true);*/
				
	}
	
}
