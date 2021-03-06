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
package edu.jhu.pha.vospace.node;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.map.MappingJsonFactory;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.util.TokenBuffer;
import org.kamranzafar.jtar.TarEntry;
import org.kamranzafar.jtar.TarHeader;
import org.kamranzafar.jtar.TarOutputStream;

import edu.jhu.pha.vospace.QueueConnector;
import edu.jhu.pha.vospace.api.exceptions.ForbiddenException;
import edu.jhu.pha.vospace.api.exceptions.InternalServerErrorException;
import edu.jhu.pha.vospace.api.exceptions.NotFoundException;
import edu.jhu.pha.vospace.meta.MetaStoreDistributed;
import edu.jhu.pha.vospace.meta.NodesList;


public class ContainerNode extends DataNode {
	
	private static final Logger logger = Logger.getLogger(ContainerNode.class);
	private static MappingJsonFactory f = new MappingJsonFactory();

    /**
     * Construct a Node from the byte array
     * @param req The byte array containing the Node
     */
    public ContainerNode(byte[] bytes, String username, VospaceId id)  {
        super(bytes, username, id);
    }

	public ContainerNode(VospaceId id, String username) {
		super(id, username);
	}
	
	@Override
	public void copy(VospaceId newLocationId, boolean keepBytes) {
		if(!isStoredMetadata())
			throw new NotFoundException("NodeNotFound");
		
		if(getMetastore().isStored(newLocationId)) {
			throw new ForbiddenException("DestinationNodeExists");
		}
		
		if(newLocationId.getNodePath().isParent(this.getUri().getNodePath())) {
			throw new ForbiddenException("Forbidden to copy into itself");
		}

		Node newDataNode = NodeFactory.createNode(newLocationId, owner, this.getType());
		newDataNode.setNode(null);
		newDataNode.getStorage().updateNodeInfo(newLocationId.getNodePath(), newDataNode.getNodeInfo());
		newDataNode.getMetastore().storeInfo(newLocationId, newDataNode.getNodeInfo());
		newDataNode.getMetastore().updateUserProperties(newLocationId, getNodeMeta(PropertyType.property));
		
		NodesList childrenList = getDirectChildren(false, 0, -1);
		List<Node> children = childrenList.getNodesList();

		for(Node child: children) {
			Node childNode = NodeFactory.getNode(child.getUri(), owner);
			String relativePath = childNode.getUri().getNodePath().getParentRelativePath(this.getUri().getNodePath());
			try {
				VospaceId newChildId = newLocationId.appendPath(new NodePath(relativePath));
				logger.debug("Copying child "+childNode.getUri()+" with relpath "+relativePath+" to "+newChildId.toString());
				childNode.copy(newChildId, keepBytes);
			} catch (URISyntaxException e) {
				logger.error("Error copying child "+childNode.getUri().toString()+": "+e.getMessage());
			}
		}

		if(!keepBytes) {
			getMetastore().remove(this.getUri());
			
			if(this.getUri().getNodePath().getNodeStoragePathArray().length == 1) { // moving first-level container to another one
				getStorage().remove(this.getUri().getNodePath(), false);
			}
		}
		
		QueueConnector.goAMQP("movedNode", new QueueConnector.AMQPWorker<Boolean>() {
			@Override
			public Boolean go(com.rabbitmq.client.Connection conn, com.rabbitmq.client.Channel channel) throws IOException {

				channel.exchangeDeclare(conf.getString("vospace.exchange.nodechanged"), "fanout", false);

				Map<String,Object> nodeData = new HashMap<String,Object>();
				nodeData.put("uri", getUri().toString());
				nodeData.put("owner",getOwner());
    			nodeData.put("container", getUri().getNodePath().getParentPath().getNodeStoragePath());

    			byte[] jobSer = (new ObjectMapper()).writeValueAsBytes(nodeData);
    			channel.basicPublish(conf.getString("vospace.exchange.nodechanged"), "", null, jobSer);
		    	
		    	return true;
			}
		});
	}

    @Override
	public Object export(String format, Detail detail, boolean includeDeleted) {
    	return export(format, detail, 0, -1, includeDeleted);
    }
    
	public Object export(String format, Detail detail, int start, int count, boolean includeDeleted) {
		if(format.equals("json-dropbox") || format.equals("json-dropbox-object")){

	    	TokenBuffer g = new TokenBuffer(null);
	    	
			try {
	        	g.writeStartObject();
				
	        	g.writeStringField("size", readableFileSize(getNodeInfo().getSize()));
	        	g.writeNumberField("rev", getNodeInfo().getRevision());
				g.writeBooleanField("thumb_exists", false);
				g.writeNumberField("bytes", getNodeInfo().getSize());
				g.writeStringField("modified", dropboxDateFormat.format(getNodeInfo().getMtime()));
				g.writeStringField("path", getUri().getNodePath().getNodeOuterPath());
				g.writeBooleanField("is_dir", true);
				if(includeDeleted || getNodeInfo().isDeleted())
					g.writeBooleanField("is_deleted", getNodeInfo().isDeleted());
				g.writeStringField("icon", "folder_public");
				g.writeStringField("root", (getUri().getNodePath().isEnableAppContainer()?"sandbox":"dropbox"));

				if(detail == Detail.max) {
					NodesList childrenList = getDirectChildren(includeDeleted, start, count);
					List<Node> childNodesList = childrenList.getNodesList();

					g.writeNumberField("items", childrenList.getNodesCount());

					// contents array
					g.writeArrayFieldStart("contents");

					for(Node childNode: childNodesList){
						JsonNode node = (JsonNode)childNode.export("json-dropbox-object", Detail.min, includeDeleted); 
						g.writeTree(node);
					}
					
					g.writeEndArray();
					// end contents array
				}
				//g.writeEndObject(); // not finished yet, add the hash, will close automatically
	        	
	        	if(detail == Detail.max){
			    	ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
					JsonGenerator g2 = f.createJsonGenerator(byteOut);
					g.serialize(g2);
					g2.close();
					byteOut.close();

					byte[] nodeContent = byteOut.toByteArray();
	        		
	        		MessageDigest md = MessageDigest.getInstance("MD5");
	        		byte[] thedigest = md.digest(nodeContent);

	        		g.writeBinaryField("hash",thedigest);

	        		// long way of adding hash, with proper object closing:
					/*JsonNode rootNode = objMapper.readTree(g.asParser(f.getCodec()));
					((ObjectNode)rootNode).put("hash",thedigest);
					
					g.close();
					g = new TokenBuffer(null);
					g.writeTree(rootNode);*/
	        	}

			} catch (JsonGenerationException e) {
				e.printStackTrace();
				throw new InternalServerErrorException("Error generationg JSON: "+e.getMessage());
			} catch (IOException e) {
				e.printStackTrace();
				throw new InternalServerErrorException("Error generationg JSON: "+e.getMessage());
			} catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
				throw new InternalServerErrorException("Error generating MD5 hash: "+e.getMessage());
			} finally {
				try {
					g.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

			if(format.equals("json-dropbox")) {
				try {
			    	ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
					JsonGenerator g2 = f.createJsonGenerator(byteOut); //.useDefaultPrettyPrinter() - not working with metadata header
					g.serialize(g2);
					g2.close();
					byteOut.close();
	
					return byteOut.toByteArray();
				} catch (IOException e1) { // shouldn't happen
					logger.error("Error creating JSON generator: "+e1.getMessage());
					throw new InternalServerErrorException("Error creating JSON generator: "+e1.getMessage());
				}

			} else {
				try {
					return g.asParser(f.getCodec()).readValueAsTree();
				} catch (JsonProcessingException e) {
					logger.error("Error generating JSON: "+e.getMessage());
					throw new InternalServerErrorException("Error generating JSON: "+e.getMessage());
				} catch (IOException e) {
					logger.error("Error generating JSON: "+e.getMessage());
					throw new InternalServerErrorException("Error generating JSON: "+e.getMessage());
				}
			}

		} else {
			return getXmlMetadata(detail).getBytes();
		}
	}

	@Override
	public InputStream exportData() {
		final PipedInputStream pipedIn = new PipedInputStream();
		PipedOutputStream pipedOut = null;

		try {
			pipedOut = new PipedOutputStream(pipedIn);
			//final TarOutputStream tarOut = new TarOutputStream(new GZIPOutputStream(pipedOut));
			final TarOutputStream tarOut = new TarOutputStream(pipedOut);
    		Runnable runTransfer = new Runnable() {
    	        @Override
				public void run() {
    	        	try {
    		        	tarContainer("", tarOut);
    	        	} catch(IOException ex) {
    	        		logger.error(ex.getMessage());
    	        	} finally {
    	        		try {
    	        			if(null != tarOut)
    	        				tarOut.close();
    	        		} catch(Exception ex) {}
    	        	}
    	        	logger.debug("Finishing node "+getUri()+" thread");
    	        }
    	      };

    	      Thread threadA = new Thread(runTransfer, "folderDownloadThread");
    	      threadA.start();
    	      return pipedIn;
		} catch (IOException e) {
			logger.debug("Error connectiong container output streams");
			e.printStackTrace();
		}
		return null;
	}
	
	protected void tarContainer(String parent, TarOutputStream out) throws IOException {
		NodesList childrenList = getDirectChildren(false, 0, -1);
		List<Node> children = childrenList.getNodesList();

		parent = parent + getUri().getNodePath().getNodeName()+"/";
		
		for(Node child: children) {
			if(child.getType() == NodeType.CONTAINER_NODE) {
				out.putNextEntry(new TarEntry(TarHeader.createHeader(parent+child.getUri().getNodePath().getNodeName(), 0, child.getNodeInfo().getMtime().getTime()/1000, true)));
				((ContainerNode)child).tarContainer(parent, out);
			} else {
				try {
					InputStream nodeInpStream = child.exportData();
					out.putNextEntry(new TarEntry(TarHeader.createHeader(parent+child.getUri().getNodePath().getNodeName(), child.getNodeInfo().getSize(), child.getNodeInfo().getMtime().getTime()/1000, false)));
					IOUtils.copyLarge(nodeInpStream, out);
					nodeInpStream.close();
				} catch(Exception ex) {}
			}
		}
	}

	
    /**
     * Retrieves the list of node's children
     * @param includeDeleted Include the nodes marked as deleted in the database
     * @return
     */
    public NodesList getDirectChildren(boolean includeDeleted, int start, int count) {
		return getMetastore().getNodeChildren(getUri(), false, includeDeleted, start, count);
    }

    /**
     * Retrieves information about the nodes sync regions from metadata
     * @return Empty list if no regions or list of sync regions
     */
    public List<String> getNodeRegions() {
    	if(getMetastore() instanceof MetaStoreDistributed) {
    		return ((MetaStoreDistributed)getMetastore()).getNodeRegions(getUri());
    	}
    	throw new InternalServerErrorException("Unsupported");
    }
    
    /**
     * Returns information about node's current storage SyncTo metadata
     * @return
     */
    public String getNodeSyncTo() {
		return getStorage().getNodeSyncAddress(getUri().getNodePath().getContainerName());
    }
    
	@Override
	public NodeType getType() {
		return NodeType.CONTAINER_NODE;
	}
	
	@Override
	public void markRemoved(boolean isRemoved) {
		if(!isStoredMetadata())
			throw new NotFoundException("NodeNotFound");

		NodesList childrenList = getDirectChildren(false, 0, -1);
		List<Node> children = childrenList.getNodesList();
		for(Node child: children) {
			child.markRemoved(isRemoved);
		}

		getMetastore().markRemoved(getUri(), isRemoved);
		QueueConnector.goAMQP("mark removed Container", new QueueConnector.AMQPWorker<Boolean>() {
			@Override
			public Boolean go(com.rabbitmq.client.Connection conn, com.rabbitmq.client.Channel channel) throws IOException {

				channel.exchangeDeclare(conf.getString("vospace.exchange.nodechanged"), "fanout", false);

				Map<String,Object> nodeData = new HashMap<String,Object>();
				nodeData.put("uri",getUri().toString());
				nodeData.put("owner",getOwner());
    			nodeData.put("container", getUri().getNodePath().getParentPath().getNodeStoragePath());

    			byte[] jobSer = (new ObjectMapper()).writeValueAsBytes(nodeData);
    			channel.basicPublish(conf.getString("vospace.exchange.nodechanged"), "", null, jobSer);
		    	
		    	return true;
			}
		});
	}

	public List<VospaceId> search(String query, int fileLimit, boolean includeDeleted) {
		return getMetastore().search(getUri(), query, fileLimit, includeDeleted);
    }
	
	/**
	 * Upload a new file to the container
	 * @param filename The new file name
	 * @param data The new file content
	 */
	public void setData(String filename, InputStream data) {
		try {
			VospaceId newNodeUri = getUri().appendPath(new NodePath(filename));
			getStorage().putBytes(newNodeUri.getNodePath(), data);
			if(!getMetastore().isStored(newNodeUri)){
				DataNode node = NodeFactory.<DataNode>createNode(newNodeUri, owner, NodeType.DATA_NODE);
				node.setNode(null);
			}
		} catch (URISyntaxException e) {
			throw new InternalServerErrorException("InvalidURI");
		}
	}

	/**
	 * Set node regions in metadata DB
	 * @param regions
	 */
	public void setNodeRegions(Map<String, String> regions) {
    	if(getMetastore() instanceof MetaStoreDistributed) {
    		((MetaStoreDistributed)getMetastore()).setNodeRegions(this.getUri(), regions);
    	}
    	throw new InternalServerErrorException("Unsupported");
	}

    public void setNodeSyncTo(String syncToUrl, String syncKey) {
		getStorage().setNodeSyncTo(getUri().getNodePath().getContainerName(), syncToUrl, syncKey);
    }

    @Override
	public void makeNodeStructured(boolean isStructured) {
		throw new AssertionError("Can't change ContainerNode type to DataNode");
	}

}
