package smithereen.activitypub.objects.activities;

import smithereen.activitypub.objects.Activity;

public class Announce extends Activity{
	@Override
	public String getType(){
		return "Announce";
	}
}
