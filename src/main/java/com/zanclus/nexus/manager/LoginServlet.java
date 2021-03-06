package com.zanclus.nexus.manager;

import static javax.servlet.http.HttpServletResponse.*;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * @author <a href="mailto: deven.phillips@sungardas.com">Deven Phillips</a>
 *
 */
public class LoginServlet extends HttpServlet {

	private static final Logger LOG = LoggerFactory.getLogger(LoginServlet.class);

	/**
	 * 
	 */
	private static final long serialVersionUID = -2911214172496395589L;

	private int nexus_port;
	private String nexus_host;
	private String nexus_proto;
	private String nexus_uri;
	private String nexusURL;

	@Override
	public void init(ServletConfig config) throws ServletException {
		super.init(config);
		nexus_proto = config.getInitParameter("nexus_proto");
		nexus_host = config.getInitParameter("nexus_host");
		try {
			nexus_port = Integer.parseInt(config.getInitParameter("nexus_port"));
		} catch (NumberFormatException e) {
			nexus_port = 8080;
		}
		nexus_uri = config.getInitParameter("nexus_uri");
		nexusURL = nexus_proto+"://"+nexus_host+":"+nexus_port+nexus_uri;
	}

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		Boolean isAdmin = Boolean.FALSE;
		if (	req.getParameter("username")!=null && 
				req.getParameter("password")!=null) {
			String user = (String) req.getParameter("username");
			String pass = (String) req.getParameter("password");

			HttpClient client = new HttpClient();
			client.getParams().setAuthenticationPreemptive(true);
			Credentials cred = new UsernamePasswordCredentials(user, pass);
			client.getState()
					.setCredentials(new AuthScope(
											nexus_host, 
											nexus_port, 
											AuthScope.ANY_REALM), 
									cred);
			GetMethod get = new GetMethod(nexusURL);
			get.setRequestHeader("Accept", "application/json");
			
			try (OutputStream out = resp.getOutputStream()){
				int code = client.executeMethod(get);
				if (code!=200) {
					LOG.warn("Login failed.");
					resp.setStatus(SC_UNAUTHORIZED);
					resp.getOutputStream().close();
				} else {
					req.getSession().setAttribute("authenticated", Boolean.TRUE);
					req.getSession().setAttribute("username", user);
					
					// Parse the Nexus login response and see if the user is an admin
					String body = get.getResponseBodyAsString();
					LOG.debug(body);
					req.getSession().setAttribute("nexusAccount", body);
					GsonBuilder builder = new GsonBuilder();
					Gson gson = builder.setPrettyPrinting().create();
					JsonObject info = gson.fromJson(body, JsonObject.class);
					if (	info.get("data")!=null && 
							info.get("data").getAsJsonObject().get("clientPermissions")!=null &&
							info.get("data").getAsJsonObject().get("clientPermissions").getAsJsonObject().get("permissions")!=null) {
						JsonArray permissions = info.get("data")
														.getAsJsonObject()
														.get("clientPermissions")
														.getAsJsonObject()
														.get("permissions")
														.getAsJsonArray();
						for (JsonElement entry: permissions) {
							if (entry.isJsonObject() && !isAdmin) {
								String id = entry.getAsJsonObject().get("id").getAsString();
								if (id.toLowerCase().contentEquals("security:userssetpw")) {
									if (entry.getAsJsonObject().get("value").getAsInt()==15) {
										LOG.debug("User has ADMIN privileges.");
										isAdmin = Boolean.TRUE;
									}
								}
							}
						}
					}
					resp.setStatus(SC_OK);
					resp.setContentType("application/json");
					req.getSession().setAttribute("is_admin", isAdmin);
					out.write(info.toString().getBytes());
					out.close();
				}
			} catch (IOException ioe) {
				resp.setStatus(SC_PRECONDITION_FAILED);
				req.getSession().setAttribute("is_admin", isAdmin);
				OutputStream out = resp.getOutputStream();
				PrintWriter pw = new PrintWriter(out);
				pw.println("Unable to connect to Nexus server at '"+nexusURL+"' to perform authentication.");
				ioe.printStackTrace(pw);
				pw.close();
				out.close();
			}
		} else {
			req.getSession().setAttribute("is_admin", isAdmin);
			LOG.warn("No username and password specified");
			resp.setStatus(SC_BAD_REQUEST);
			resp.getOutputStream().close();
		}
	}

	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		this.doGet(req, resp);
	}

	@Override
	protected void doPut(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		this.doGet(req, resp);
	}

	/* (non-Javadoc)
	 * @see javax.servlet.http.HttpServlet#doOptions(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
	 */
	@Override
	protected void doOptions(HttpServletRequest req, HttpServletResponse resp)
			throws ServletException, IOException {
		String acctInfo = (String)req.getSession().getAttribute("nexusAccount");
		if (acctInfo!=null) {
			try (OutputStream out = resp.getOutputStream()) {
				out.write(((String) req.getSession().getAttribute(
						"nexusAccount")).getBytes());
				resp.setHeader("Allow", "GET,POST,PUT,OPTIONS");
				resp.setStatus(SC_OK);
			} catch (IOException ioe) {
				resp.setStatus(SC_INTERNAL_SERVER_ERROR);
			}
		} else {
			resp.setStatus(SC_UNAUTHORIZED);
			resp.getOutputStream().close();
		}
	}
}
