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
package edu.jhu.pha.vospace.api.exceptions;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

/**
 * Exception for arising the PermissionDeniedError HTTP error response
 * @author Dmitry Mishin
 *
 */
public class PermissionDeniedException extends WebApplicationException {

	private static final long serialVersionUID = 5123453394868009076L;

	public PermissionDeniedException(String message) {
		super(Response.status(Status.UNAUTHORIZED).entity(message).type("text/plain").build());
	}

	public PermissionDeniedException(Throwable cause) {
		super(cause, Response.status(Status.UNAUTHORIZED).entity(cause.getMessage()).type("text/plain").build());
	}

	public PermissionDeniedException(Throwable cause, String message) {
		super(cause, Response.status(Status.UNAUTHORIZED).entity(message).type("text/plain").build());
	}
}
