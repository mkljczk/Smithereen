package smithereen.routes;

import org.json.JSONArray;
import org.jtwig.JtwigModel;

import java.net.URI;
import java.net.URLEncoder;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import smithereen.Config;
import static smithereen.Utils.*;

import smithereen.Utils;
import smithereen.activitypub.ActivityPub;
import smithereen.activitypub.ActivityPubWorker;
import smithereen.activitypub.objects.ActivityPubObject;
import smithereen.data.Account;
import smithereen.data.ForeignUser;
import smithereen.data.PhotoSize;
import smithereen.data.SessionInfo;
import smithereen.data.WebDeltaResponseBuilder;
import smithereen.data.attachments.Attachment;
import smithereen.data.attachments.PhotoAttachment;
import smithereen.data.feed.NewsfeedEntry;
import smithereen.data.Post;
import smithereen.data.feed.PostNewsfeedEntry;
import smithereen.data.User;
import smithereen.data.notifications.Notification;
import smithereen.data.notifications.NotificationUtils;
import smithereen.storage.MediaCache;
import smithereen.storage.MediaStorageUtils;
import smithereen.storage.NotificationsStorage;
import smithereen.storage.PostStorage;
import smithereen.storage.UserStorage;
import spark.Request;
import spark.Response;
import spark.utils.StringUtils;

public class PostRoutes{

	public static Object createWallPost(Request req, Response resp, Account self) throws Exception{
		String username=req.params(":username");
		User user=UserStorage.getByUsername(username);
		if(user!=null){
			String text=req.queryParams("text");
			if(text.length()==0 && StringUtils.isEmpty(req.queryParams("attachments")))
				return "Empty post";


			text=text.replace("\n", "<br>").replace("\r", "");
			if(!text.startsWith("<p>")){
				String[] paragraphs=text.split("(\n?<br>){2,}");
				StringBuffer sb=new StringBuffer();
				for(String paragraph:paragraphs){
					String p=paragraph.trim();
					if(p.isEmpty())
						continue;
					sb.append("<p>");
					sb.append(p);
					sb.append("</p>");
				}
				text=sb.toString();
			}
			int userID=self.user.id;
			int replyTo=Utils.parseIntOrDefault(req.queryParams("replyTo"), 0);
			int postID;

			StringBuffer sb=new StringBuffer();
			ArrayList<User> mentionedUsers=new ArrayList<>();
			Pattern mentionRegex=Pattern.compile("@([a-zA-Z0-9._-]+)(?:@([a-zA-Z0-9._-]+[a-zA-Z0-9-]+))?");
			Matcher matcher=mentionRegex.matcher(text);
			while(matcher.find()){
				String u=matcher.group(1);
				String d=matcher.group(2);
				if(d!=null && d.equalsIgnoreCase(Config.domain)){
					d=null;
				}
				User mentionedUser;
				if(d==null){
					mentionedUser=UserStorage.getByUsername(u);
				}else{
					mentionedUser=UserStorage.getByUsername(u+"@"+d);
				}
				if(d!=null && mentionedUser==null){
					try{
						URI uri=ActivityPub.resolveUsername(u, d);
						System.out.println(u+"@"+d+" -> "+uri);
						ActivityPubObject obj=ActivityPub.fetchRemoteObject(uri.toString());
						if(obj instanceof ForeignUser){
							mentionedUser=(User) obj;
							UserStorage.putOrUpdateForeignUser((ForeignUser) obj);
						}
					}catch(Exception x){
						System.out.println("Can't resolve "+u+"@"+d+": "+x.getMessage());
					}
				}
				if(mentionedUser!=null){
					matcher.appendReplacement(sb, "<span class=\"h-card\"><a href=\""+mentionedUser.url+"\" class=\"u-url mention\">$0</a></span>");
					mentionedUsers.add(mentionedUser);
				}else{
					System.out.println("ignoring mention "+matcher.group());
					matcher.appendReplacement(sb, "$0");
				}
			}
			if(!mentionedUsers.isEmpty()){
				matcher.appendTail(sb);
				text=sb.toString();
			}

			Pattern urlPattern=Pattern.compile("\\b(https?:\\/\\/)?([a-z0-9_.-]+\\.([a-z0-9_-]+))(?:\\:\\d+)?((?:\\/(?:[\\w\\.%:!+-]|\\([^\\s]+?\\))+)*)(\\?(?:\\w+(?:=(?:[\\w\\.%:!+-]|\\([^\\s]+?\\))+&?)?)+)?(#(?:[\\w\\.%:!+-]|\\([^\\s]+?\\))+)?", Pattern.CASE_INSENSITIVE);
			matcher=urlPattern.matcher(text);
			sb=new StringBuffer();
			while(matcher.find()){
				String url=matcher.group();
				String realURL=url;
				if(StringUtils.isEmpty(matcher.group(1))){
					realURL="http://"+url;
				}
				int start=matcher.start();
				if(start>10){
					String before=text.substring(0, start);
					if(before.endsWith("href=\"") || before.endsWith("@")){
						matcher.appendReplacement(sb, url);
						continue;
					}
				}
				matcher.appendReplacement(sb, "<a href=\""+realURL+"\">"+url+"</a>");
			}
			matcher.appendTail(sb);
			text=sb.toString();

			text=Utils.sanitizeHTML(text).trim();

			String attachments=null;
			if(StringUtils.isNotEmpty(req.queryParams("attachments"))){
				ArrayList<ActivityPubObject> attachObjects=new ArrayList<>();
				String[] aids=req.queryParams("attachments").split(",");
				for(String id:aids){
					if(!id.matches("^[a-fA-F0-9]{32}$"))
						continue;
					ActivityPubObject obj=MediaCache.getAndDeleteDraftAttachment(id, self.id);
					if(obj!=null)
						attachObjects.add(obj);
				}
				if(!attachObjects.isEmpty()){
					if(attachObjects.size()==1){
						attachments=MediaStorageUtils.serializeAttachment(attachObjects.get(0)).toString();
					}else{
						JSONArray ar=new JSONArray();
						for(ActivityPubObject o:attachObjects){
							ar.put(MediaStorageUtils.serializeAttachment(o));
						}
						attachments=ar.toString();
					}
				}
			}
			if(text.length()==0 && StringUtils.isEmpty(attachments))
				return "Empty post";

			Post parent=null;
			if(replyTo!=0){
				parent=PostStorage.getPostByID(replyTo);
				if(parent==null){
					resp.status(404);
					return Utils.wrapError(req, resp, "err_post_not_found");
				}
				int[] replyKey=new int[parent.replyKey.length+1];
				System.arraycopy(parent.replyKey, 0, replyKey, 0, parent.replyKey.length);
				replyKey[replyKey.length-1]=parent.id;
				// comment replies start with mentions, but only if it's a reply to a comment, not a top-level post
				if(parent.replyKey.length>0 && text.startsWith("<p>"+parent.user.getNameForReply()+", ")){
					text="<p><span class=\"h-card\"><a href=\""+parent.user.url+"\" class=\"u-url mention\">"+parent.user.getNameForReply()+"</a></span>"+text.substring(parent.user.getNameForReply().length()+3);
				}
				mentionedUsers.add(parent.user);
				if(parent.replyKey.length>1){
					Post topLevel=PostStorage.getPostByID(parent.replyKey[0]);
					if(topLevel!=null)
						mentionedUsers.add(topLevel.user);
				}
				postID=PostStorage.createUserWallPost(userID, user.id, text, replyKey, mentionedUsers, attachments);
			}else{
				postID=PostStorage.createUserWallPost(userID, user.id, text, null, mentionedUsers, attachments);
			}

			Post post=PostStorage.getPostByID(postID);
			if(post==null)
				throw new IllegalStateException("?!");
			ActivityPubWorker.getInstance().sendCreatePostActivity(post);
			NotificationUtils.putNotificationsForPost(post, parent);

			SessionInfo sess=sessionInfo(req);
			sess.postDraftAttachments.clear();
			if(isAjax(req)){
				String postHTML=Utils.renderTemplate(req, replyTo!=0 ? "wall_reply" : "wall_post", JtwigModel.newModel().with("post", post));
				resp.type("application/json");
				WebDeltaResponseBuilder rb;
				if(replyTo==0)
					rb=new WebDeltaResponseBuilder().insertHTML(WebDeltaResponseBuilder.ElementInsertionMode.AFTER_BEGIN, "postList", postHTML);
				else
					rb=new WebDeltaResponseBuilder().insertHTML(WebDeltaResponseBuilder.ElementInsertionMode.BEFORE_END, "postReplies"+replyTo, postHTML);
				String formID=req.queryParams("formID");
				return rb.setInputValue("postFormText_"+formID, "").setContent("postFormAttachments_"+formID, "").json();
			}
			resp.redirect(Utils.back(req));
		}else{
			resp.status(404);
			return Utils.wrapError(req, resp, "err_user_not_found");
		}
		return "";
	}

	public static Object feed(Request req, Response resp, Account self) throws SQLException{
		int userID=self.user.id;
		int startFromID=parseIntOrDefault(req.queryParams("startFrom"), 0);
		int offset=parseIntOrDefault(req.queryParams("offset"), 0);
		int[] total={0};
		List<NewsfeedEntry> feed=PostStorage.getFeed(userID, startFromID, offset, total);
		for(NewsfeedEntry e:feed){
			if(e instanceof PostNewsfeedEntry){
				PostNewsfeedEntry pe=(PostNewsfeedEntry) e;
				if(pe.post!=null)
					pe.post.replies=PostStorage.getRepliesForFeed(e.objectID);
				else
					System.err.println("No post: "+pe);
			}
		}
		if(!feed.isEmpty() && startFromID==0)
			startFromID=feed.get(0).id;
		Utils.jsLangKey(req, "yes", "no", "delete_post", "delete_post_confirm", "delete");
		JtwigModel model=JtwigModel.newModel().with("title", Utils.lang(req).get("feed")).with("feed", feed)
				.with("paginationURL", "/feed?startFrom="+startFromID+"&offset=").with("total", total[0]).with("offset", offset)
				.with("draftAttachments", Utils.sessionInfo(req).postDraftAttachments);
		return Utils.renderTemplate(req, "feed", model);
	}

	public static Object standalonePost(Request req, Response resp) throws SQLException{
		int postID=Utils.parseIntOrDefault(req.params(":postID"), 0);
		if(postID==0){
			resp.status(404);
			return Utils.wrapError(req, resp, "err_post_not_found");
		}
		Post post=PostStorage.getPostByID(postID);
		if(post==null){
			resp.status(404);
			return Utils.wrapError(req, resp, "err_post_not_found");
		}
		int[] replyKey=new int[post.replyKey.length+1];
		System.arraycopy(post.replyKey, 0, replyKey, 0, post.replyKey.length);
		replyKey[replyKey.length-1]=post.id;
		post.replies=PostStorage.getReplies(replyKey);
		JtwigModel model=JtwigModel.newModel();
		model.with("post", post);
		SessionInfo info=Utils.sessionInfo(req);
		if(info!=null && info.account!=null)
			model.with("draftAttachments", info.postDraftAttachments);
		if(post.replyKey.length>0){
			model.with("prefilledPostText", post.user.getNameForReply()+", ");
		}
		if(info==null || info.account==null){
			HashMap<String, String> meta=new LinkedHashMap<>();
			meta.put("og:site_name", "Smithereen");
			meta.put("og:type", "article");
			meta.put("og:title", post.user.getFullName());
			meta.put("og:url", post.url.toString());
			meta.put("og:published_time", Utils.formatDateAsISO(post.published));
			meta.put("og:author", post.user.url.toString());
			if(StringUtils.isNotEmpty(post.content)){
				meta.put("og:description", Utils.truncateOnWordBoundary(post.content, 250));
			}
			boolean hasImage=false;
			if(post.attachment!=null && !post.attachment.isEmpty()){
				for(Attachment att : post.getProcessedAttachments()){
					if(att instanceof PhotoAttachment){
						PhotoSize size=MediaStorageUtils.findBestPhotoSize(((PhotoAttachment) att).sizes, PhotoSize.Format.JPEG, PhotoSize.Type.MEDIUM);
						if(size!=null){
							meta.put("og:image", size.src.toString());
							meta.put("og:image:width", size.width+"");
							meta.put("og:image:height", size.height+"");
							hasImage=true;
						}
						break;
					}
				}
			}
			if(!hasImage){
				if(post.user.hasAvatar()){
					PhotoSize size=MediaStorageUtils.findBestPhotoSize(post.user.getAvatar(), PhotoSize.Format.JPEG, PhotoSize.Type.LARGE);
					if(size!=null){
						meta.put("og:image", size.src.toString());
						meta.put("og:image:width", size.width+"");
						meta.put("og:image:height", size.height+"");
					}
				}
			}
			model.with("metaTags", meta);
		}
		Utils.jsLangKey(req, "yes", "no", "delete_post", "delete_post_confirm", "delete_reply", "delete_reply_confirm", "delete");
		model.with("title", post.getShortTitle(50)+" | "+post.user.getFullName());
		return Utils.renderTemplate(req, "wall_post_standalone", model);
	}

	public static Object confirmDelete(Request req, Response resp, Account self) throws SQLException{
		req.attribute("noHistory", true);
		int postID=Utils.parseIntOrDefault(req.params(":postID"), 0);
		if(postID==0){
			resp.status(404);
			return Utils.wrapError(req, resp, "err_post_not_found");
		}
		String back=Utils.back(req);
		return Utils.renderTemplate(req, "generic_confirm", JtwigModel.newModel().with("message", Utils.lang(req).get("delete_post_confirm")).with("formAction", Config.localURI("/posts/"+postID+"/delete?_redir="+URLEncoder.encode(back))).with("back", back));
	}

	public static Object delete(Request req, Response resp, Account self) throws SQLException{
		int postID=Utils.parseIntOrDefault(req.params(":postID"), 0);
		if(postID==0){
			resp.status(404);
			return Utils.wrapError(req, resp, "err_post_not_found");
		}
		Post post=PostStorage.getPostByID(postID);
		if(post==null){
			resp.status(404);
			return Utils.wrapError(req, resp, "err_post_not_found");
		}
		if(!post.canBeManagedBy(self.user)){
			resp.status(403);
			return Utils.wrapError(req, resp, "err_access");
		}
		PostStorage.deletePost(post.id);
		NotificationsStorage.deleteNotificationsForObject(Notification.ObjectType.POST, post.id);
		if(Config.isLocal(post.activityPubID) && post.attachment!=null && !post.attachment.isEmpty()){
			MediaStorageUtils.deleteAttachmentFiles(post.attachment);
		}
		ActivityPubWorker.getInstance().sendDeletePostActivity(post);
		if(isAjax(req)){
			resp.type("application/json");
			return new WebDeltaResponseBuilder().remove("post"+postID).json();
		}
		resp.redirect(Utils.back(req));
		return "";
	}
}
