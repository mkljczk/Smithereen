package smithereen.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import smithereen.Config;
import smithereen.Utils;
import smithereen.activitypub.ActivityPub;
import smithereen.activitypub.ContextCollector;
import smithereen.activitypub.ParserContext;
import smithereen.activitypub.objects.ActivityPubCollection;
import smithereen.activitypub.objects.ActivityPubObject;
import smithereen.activitypub.objects.Actor;
import smithereen.activitypub.objects.CollectionPage;
import smithereen.activitypub.objects.Image;
import smithereen.activitypub.objects.LinkOrObject;
import smithereen.activitypub.objects.LocalImage;
import smithereen.activitypub.objects.Mention;
import smithereen.data.attachments.Attachment;
import smithereen.data.attachments.PhotoAttachment;
import smithereen.data.attachments.VideoAttachment;
import smithereen.storage.GroupStorage;
import smithereen.storage.MediaCache;
import smithereen.storage.PostStorage;
import smithereen.storage.UserStorage;
import spark.utils.StringUtils;

public class Post extends ActivityPubObject{
	public int id;
	public User user;
	public Actor owner;
	private boolean deleted;

	public String userLink;

	public int[] replyKey={};

	public List<Post> replies=new ArrayList<>();
	public boolean local;
	public List<User> mentionedUsers=Collections.EMPTY_LIST;

	public static Post fromResultSet(ResultSet res) throws SQLException{
		Post post=new Post();
		post.fillFromResultSet(res);
		return post;
	}

	protected void fillFromResultSet(ResultSet res) throws SQLException{
		id=res.getInt("id");

		String apid=res.getString("ap_id");
		if(apid==null){
			activityPubID=Config.localURI("/posts/"+id);
			url=activityPubID;
			local=true;
		}else{
			activityPubID=URI.create(apid);
			url=URI.create(res.getString("ap_url"));
		}

		byte[] rk=res.getBytes("reply_key");
		replyKey=Utils.deserializeIntArray(rk);
		if(replyKey==null)
			replyKey=new int[0];

		if(replyKey.length>0){
			inReplyTo=PostStorage.getActivityPubID(replyKey[replyKey.length-1]);
		}

		int uid=res.getInt("author_id");
		if(!res.wasNull()){
			user=UserStorage.getById(uid);
			int ownerUserID=res.getInt("owner_user_id");
			if(!res.wasNull())
				owner=UserStorage.getById(ownerUserID);
			else
				owner=GroupStorage.getByID(res.getInt("owner_group_id"));
		}else{
			deleted=true;
			return;
		}

		content=res.getString("text");
		published=res.getTimestamp("created_at");
		summary=res.getString("content_warning");
		attributedTo=user.activityPubID;

		if(owner instanceof User && user.id==((User)owner).id){
			to=Collections.singletonList(new LinkOrObject(ActivityPub.AS_PUBLIC));
			if(replyKey.length==0)
				cc=Collections.singletonList(new LinkOrObject(user.getFollowersURL()));
			else
				cc=Collections.emptyList();
		}else if(owner instanceof Group){
			to=Collections.singletonList(new LinkOrObject(owner.getFollowersURL()));
			cc=Collections.singletonList(new LinkOrObject(ActivityPub.AS_PUBLIC));
		}else{
			to=Collections.emptyList();
			cc=Arrays.asList(new LinkOrObject(ActivityPub.AS_PUBLIC), new LinkOrObject(owner.activityPubID));
		}

		String att=res.getString("attachments");
		if(att!=null){
			try{
				attachment=parseSingleObjectOrArray(att.charAt(0)=='[' ? new JSONArray(att) : new JSONObject(att), ParserContext.LOCAL);
			}catch(Exception ignore){}
		}

		userLink=user.url.toString();

		int[] mentions=Utils.deserializeIntArray(res.getBytes("mentions"));
		if(mentions!=null && mentions.length>0){
			mentionedUsers=new ArrayList<>();
			if(tag==null)
				tag=new ArrayList<>();
			for(int id:mentions){
				User user=UserStorage.getById(id);
				if(user!=null){
					mentionedUsers.add(user);
					addToCC(user.activityPubID);
					Mention mention=new Mention();
					mention.href=user.activityPubID;
					tag.add(mention);
				}
			}
		}
	}

	public boolean hasContentWarning(){
		return summary!=null;
	}

	@Override
	public String getType(){
		if(deleted)
			return "Tombstone";
		return "Note";
	}

	@Override
	public JSONObject asActivityPubObject(JSONObject obj, ContextCollector contextCollector){
		JSONObject root=super.asActivityPubObject(obj, contextCollector);

		ActivityPubCollection replies=new ActivityPubCollection(false);
		replies.activityPubID=Config.localURI("/posts/"+id+"/replies");
		CollectionPage repliesPage=new CollectionPage(false);
		repliesPage.next=Config.localURI("/posts/"+id+"/replies?offset=0&count=50");
		repliesPage.partOf=replies.activityPubID;
		repliesPage.items=Collections.EMPTY_LIST;
		replies.first=new LinkOrObject(repliesPage);
		root.put("replies", replies.asActivityPubObject(new JSONObject(), contextCollector));

		if(deleted){
			root.put("formerType", "Note");
			return root;
		}
		root.put("sensitive", hasContentWarning());
		contextCollector.addAlias("sensitive", "as:sensitive");
		if(root.has("content"))
			root.put("content", Utils.postprocessPostHTMLForActivityPub(content));

		if(getReplyLevel()==0 && (!(owner instanceof User) || user.id!=((User)owner).id)){
			ActivityPubCollection wall=new ActivityPubCollection(false);
			wall.activityPubID=owner.getWallURL();
			wall.attributedTo=owner.activityPubID;
			root.put("partOf", wall.asActivityPubObject(new JSONObject(), contextCollector));
		}

		return root;
	}

	@Override
	protected ActivityPubObject parseActivityPubObject(JSONObject obj, ParserContext parserContext) throws Exception{
		super.parseActivityPubObject(obj, parserContext);
		Object _content=obj.get("content");
		if(_content instanceof JSONArray){
			content=((JSONArray) _content).getString(0);
		}
		if(!parserContext.isLocal){
			content=Utils.sanitizeHTML(content);
			if(StringUtils.isNotEmpty(summary))
				summary=Utils.sanitizeHTML(summary);
		}
		user=UserStorage.getUserByActivityPubID(attributedTo);
		if(url==null)
			url=activityPubID;
		if(published==null)
			published=new Date();

		ActivityPubObject partOf=parse(obj.optJSONObject("partOf"), parserContext);
		if(partOf instanceof ActivityPubCollection && partOf.attributedTo!=null && partOf.activityPubID!=null && inReplyTo==null){
			URI ownerID=partOf.attributedTo;
			if(Config.isLocal(ownerID)){
				String[] parts=ownerID.getPath().split("/");
				if(parts.length==3){ // "", "users", id
					int id=Utils.parseIntOrDefault(parts[2], 0);
					if("users".equals(parts[1])){
						owner=UserStorage.getById(id);
						if(owner instanceof ForeignUser)
							owner=null;
					}else if("groups".equals(parts[1])){
						owner=GroupStorage.getByID(id);
						if(owner instanceof ForeignGroup)
							owner=null;
					}
				}
			}else{
				owner=UserStorage.getForeignUserByActivityPubID(ownerID);
				if(owner==null)
					owner=GroupStorage.getForeignGroupByActivityPubID(ownerID);
			}
			if(owner!=null && !partOf.activityPubID.equals(owner.getWallURL()))
				owner=null;
		}else{
			owner=user;
		}
		return this;
	}

	public String serializeAttachments(){
		if(attachment==null)
			return null;
		return serializeObjectArrayCompact(attachment, new ContextCollector()).toString();
	}

	public boolean canBeManagedBy(User user){
		if(user==null)
			return false;
		return (owner instanceof User && ((User)owner).id==user.id) || this.user.id==user.id;
	}

	public URI getInternalURL(){
		return Config.localURI("/posts/"+id);
	}

	public void setParent(Post parent){
		replyKey=new int[parent.replyKey.length+1];
		System.arraycopy(parent.replyKey, 0, replyKey, 0, parent.replyKey.length);
		replyKey[replyKey.length-1]=parent.id;
		inReplyTo=parent.activityPubID;
		if(tag==null)
			tag=new ArrayList<>();
		else if(!(tag instanceof ArrayList))
			tag=new ArrayList<>(tag);
		Mention mention=new Mention();
		mention.href=parent.user.activityPubID;
		tag.add(mention);
		if(mentionedUsers.isEmpty())
			mentionedUsers=new ArrayList<>();
		mentionedUsers.add(parent.user);
		owner=parent.owner;
	}

	public int getReplyLevel(){
		return replyKey.length;
	}

	// Reply key for posts that reply to this one.
	public int[] getReplyKeyForReplies(){
		int[] r=new int[replyKey.length+1];
		System.arraycopy(replyKey, 0, r, 0, replyKey.length);
		r[r.length-1]=id;
		return r;
	}

	public boolean isDeleted(){
		return deleted;
	}

	// for use in templates
	public int getReplyChainElement(int level){
		return replyKey[level];
	}

	public void addToCC(URI uri){
		LinkOrObject l=new LinkOrObject(uri);
		if(!cc.contains(l)){
			if(!(cc instanceof ArrayList)){
				cc=new ArrayList<>(cc);
			}
			cc.add(l);
		}
	}

	public List<Attachment> getProcessedAttachments() throws SQLException{
		ArrayList<Attachment> result=new ArrayList<>();
		int i=0;
		for(ActivityPubObject o:attachment){
			String mediaType=o.mediaType==null ? "" : o.mediaType;
			if(o instanceof Image || mediaType.startsWith("image/")){
				PhotoAttachment att=new PhotoAttachment();
				if(o instanceof LocalImage){
					LocalImage li=((LocalImage) o);
					att.image=li;
				}else{
					MediaCache.PhotoItem item=(MediaCache.PhotoItem) MediaCache.getInstance().get(o.url);
					if(item!=null){
						att.image=new CachedRemoteImage(item);
					}else{
						SizedImage.Dimensions size=SizedImage.Dimensions.UNKNOWN;
						if(o instanceof Image){
							Image im=(Image) o;
							if(im.width>0 && im.height>0){
								size=new SizedImage.Dimensions(im.width, im.height);
							}
						}
						att.image=new NonCachedRemoteImage(new NonCachedRemoteImage.PostPhotoArgs(id, i), size);
					}
				}
				result.add(att);
			}else if(mediaType.startsWith("video/")){
				VideoAttachment att=new VideoAttachment();
				att.url=o.url;
				result.add(att);
			}
			i++;
		}
		return result;
	}

	public String getShortTitle(){
		return getShortTitle(100);
	}

	public String getShortTitle(int maxLen){
		if(StringUtils.isNotEmpty(summary)){
			return summary;
		}
		if(StringUtils.isNotEmpty(content)){
			return Utils.truncateOnWordBoundary(content, maxLen);
		}
		return "";
	}

	public void getAllReplyIDs(ArrayList<Integer> out){
		for(Post reply:replies){
			out.add(reply.id);
			reply.getAllReplyIDs(out);
		}
	}

	public boolean isGroupOwner(){
		return owner instanceof Group;
	}
}
