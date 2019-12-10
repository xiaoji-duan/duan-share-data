package com.xiaoji.duan.shs;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.util.StringUtils;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.TemplateHandler;
import io.vertx.ext.web.templ.thymeleaf.ThymeleafTemplateEngine;

/**
 * 
 * 冥王星日程或其它数据分享 数据存储在阿里云本地,按日期存储,超过日期自动删除 直接根据读取数据显示页面,如果过期,则显示过期页面
 * 
 * @author xiaoji
 *
 */
public class MainVerticle extends AbstractVerticle {

	private ThymeleafTemplateEngine thymeleaf = null;
	private ThymeleafTemplateEngine thymeleafcsv = null;

	@Override
	public void start(Promise<Void> startPromise) throws Exception {

		vertx.exceptionHandler(exception -> {
			exception.printStackTrace();
		});
		
		thymeleaf = ThymeleafTemplateEngine.create(vertx);

		ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
		resolver.setSuffix(".html");
		resolver.setCacheable(false);
		resolver.setTemplateMode("HTML5");
		resolver.setCharacterEncoding("utf-8");
		thymeleaf.getThymeleafTemplateEngine().setTemplateResolver(resolver);

		thymeleafcsv = ThymeleafTemplateEngine.create(vertx);

		ClassLoaderTemplateResolver csvresolver = new ClassLoaderTemplateResolver();
		csvresolver.setSuffix(".csv");
		csvresolver.setCacheable(false);
		csvresolver.setTemplateMode(TemplateMode.TEXT);
		csvresolver.setCharacterEncoding("utf-8");
		thymeleafcsv.getThymeleafTemplateEngine().setTemplateResolver(csvresolver);

		Router router = Router.router(vertx);
		
		Set<HttpMethod> allowedMethods = new HashSet<HttpMethod>();
		allowedMethods.add(HttpMethod.OPTIONS);
		allowedMethods.add(HttpMethod.GET);
		allowedMethods.add(HttpMethod.POST);
		allowedMethods.add(HttpMethod.PUT);
		allowedMethods.add(HttpMethod.DELETE);
		allowedMethods.add(HttpMethod.CONNECT);
		allowedMethods.add(HttpMethod.PATCH);
		allowedMethods.add(HttpMethod.HEAD);
		allowedMethods.add(HttpMethod.TRACE);

		router.route().handler(CorsHandler.create("*")
				.allowedMethods(allowedMethods)
				.allowedHeader("*")
				.allowedHeader("Content-Type")
				.allowedHeader("lt")
				.allowedHeader("pi")
				.allowedHeader("pv")
				.allowedHeader("di")
				.allowedHeader("dt")
				.allowedHeader("ai"));
		
		StaticHandler staticfiles = StaticHandler.create().setCachingEnabled(false).setWebRoot("static");
		router.route("/shs/static/*").handler(staticfiles);
		router.route("/shs").pathRegex("\\/.+\\.json").handler(staticfiles);

		router.route("/shs/:datatype/share").handler(BodyHandler.create());
		router.route("/shs/:datatype/html/:day/:dataid").handler(BodyHandler.create());
		
		router.route("/shs/:datatype/share").consumes("application/json").produces("application/json").handler(this::sharedata);
		router.route("/shs/:datatype/html/:day/:shareid").handler(this::shareview);
		router.route("/shs/:datatype/download/:day/:shareid").handler(this::shareview);

		router.route("/shs/:datatype/html/:day/:shareid").handler(ctx -> {
			String datatype = ctx.pathParam("datatype");
			JsonObject data = new JsonObject(ctx.data());
			String day = ctx.pathParam("day");
			String shareid = ctx.pathParam("shareid");
			data.put("datatype", datatype);
			data.put("day", day);
			data.put("shareid", shareid);
			Boolean exist = data.getBoolean("exist", Boolean.FALSE);
			
			if (exist) {
				thymeleaf.render(data, "/templates/" + datatype + "/view", res -> {
					if (res.succeeded()) {
						ctx.response().putHeader("Content-Type", "text/html").end(res.result());
					} else {
						ctx.fail(res.cause());
					}
				});
			} else {
				thymeleaf.render(data, "/templates/404", res -> {
					if (res.succeeded()) {
						ctx.response().putHeader("Content-Type", "text/html").end(res.result());
					} else {
						ctx.fail(res.cause());
					}
				});
			}
		});

		router.route("/shs/:datatype/download/:day/:shareid").handler(ctx -> {
			String datatype = ctx.pathParam("datatype");
			String day = ctx.pathParam("day");
			String shareid = ctx.pathParam("shareid");
			JsonObject data = new JsonObject(ctx.data());
			data.put("datatype", datatype);
			data.put("day", day);
			data.put("shareid", shareid);
			Boolean exist = data.getBoolean("exist", Boolean.FALSE);
			
			if (exist) {
				thymeleafcsv.render(data, "/templates/" + datatype + "/download", res -> {
					if (res.succeeded()) {
						ctx.response()
						.putHeader("Content-Type", "text/csv")
						.putHeader("Content-Disposition", "attachment; filename=\"" + shareid + ".csv\"")
						.end(res.result());
					} else {
						ctx.fail(res.cause());
					}
				});
			} else {
				thymeleaf.render(data, "/templates/404", res -> {
					if (res.succeeded()) {
						ctx.response().putHeader("Content-Type", "text/html").end(res.result());
					} else {
						ctx.fail(res.cause());
					}
				});
			}
		});

		HttpServerOptions option = new HttpServerOptions();
		option.setCompressionSupported(true);

		vertx.createHttpServer(option).requestHandler(router::accept).listen(config().getInteger("http.port", 8080), http -> {
			if (http.succeeded()) {
				startPromise.complete();
				System.out.println("HTTP server started on http://localhost:8080");
			} else {
				startPromise.fail(http.cause());
			}
		});
	}
	
	private void sharedata(RoutingContext ctx) {
		String datatype = ctx.pathParam("datatype");

		String body = ctx.getBodyAsString();
		if (StringUtils.isEmpty(body)) {
			ctx.response().putHeader("Content-Type", "application/json;charset=UTF-8").end(new JsonObject().encode());
			return;
		}
		
		JsonObject req = ctx.getBodyAsJson();
		
		if (req == null) {
			ctx.response().putHeader("Content-Type", "application/json;charset=UTF-8").end(new JsonObject().encode());
			return;
		}
		
		String day = new SimpleDateFormat("yyyyMMdd").format(new Date());
		
		String shareid = UUID.randomUUID().toString();
		
		String dirpath = config().getString("share.root", "/opt/duan/shs/shares/") + day + "/" + datatype.toLowerCase();
		String filepath = dirpath + "/" + shareid + ".json";
		
		JsonObject data = req.getJsonObject("payload", new JsonObject());
		data.put("share_from", req.getJsonObject("from", new JsonObject()));
		
		vertx.fileSystem().mkdirsBlocking(dirpath);
		vertx.fileSystem().writeFile(filepath, data.toBuffer(), write -> {
			JsonObject resp = new JsonObject();

			JsonObject retdata = new JsonObject();
			retdata.put("shsurl", config().getString("link.share", "https://pluto.guobaa.com/shs/") + datatype.toLowerCase() + "/html/" + day + "/" + shareid);
			
			resp.put("d", retdata);
			if (write.succeeded()) {
				ctx.response().putHeader("Content-Type", "application/json;charset=UTF-8").end(retdata.encode());
			} else {
				ctx.response().putHeader("Content-Type", "application/json;charset=UTF-8").end(retdata.encode());
			}
		});
		
	}
	
	private void shareview(RoutingContext ctx) {
		String datatype = ctx.pathParam("datatype");
		String day = ctx.pathParam("day");
		String shareid = ctx.pathParam("shareid");
		
		String filepath = config().getString("share.root", "d:\\shares\\") + day + "/" + datatype.toLowerCase() + "/" + shareid + ".json";
		
		vertx.fileSystem().exists(filepath, exists -> {
			if (exists.succeeded()) {
				Boolean isExists = exists.result();
				
				if (isExists) {
					vertx.fileSystem().readFile(filepath, readfile -> {
						if (readfile.succeeded()) {
							Buffer filebuffer = readfile.result();

							JsonObject filejson = new JsonObject(filebuffer);
							
							ctx.put("exist", Boolean.TRUE);
							ctx.put("data", filejson.mapTo(Map.class));

							ctx.next();
						} else {
							ctx.put("exist", Boolean.FALSE);
							ctx.next();
						}
					});
					
				} else {
					ctx.put("exist", Boolean.FALSE);
					ctx.next();
				}

			} else {
				ctx.put("exist", Boolean.FALSE);
				ctx.next();
			}
		});
		
	}
}
