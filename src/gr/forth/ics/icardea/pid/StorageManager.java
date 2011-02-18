package gr.forth.ics.icardea.pid;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.QueryBuilder;

/**
 * StorageManager is the class responsible for storing, retrieving, and
 * searching in the Database This implementation is using MongoDB document
 * database
 * 
 * @author ssfak
 * 
 */
final class StorageManager {
	public static final String DB_NAME = "pid";
	public static final String COLL_NAME = "patients";
	
	private Mongo mon_;
	private DB db_;
	
	/** This is a singleton class.
	 * The Java MongoDB driver is thread safe using a pool of 
	 * connections shared amongst threads. 
	 * See: http://www.mongodb.org/display/DOCS/Java+Driver+Concurrency 
	 */
	private StorageManager() {}

	/**
	 * SingletonHolder is loaded on the first execution of Singleton.getInstance() 
	 * or the first access to SingletonHolder.INSTANCE, not before.
	 */
	private static class SingletonHolder { 
		public static final StorageManager INSTANCE = new StorageManager();
	}

	public static StorageManager getInstance() {
		return SingletonHolder.INSTANCE;
	}
	 
	public void connect(String host) throws Exception {
		this.mon_ = new Mongo(host);
		this.db_ = this.mon_.getDB(DB_NAME);		
	}
	
	public void insert_pid(iCARDEA_Patient tr) {
		DBCollection coll = this.db_.getCollection(COLL_NAME);
		DBObject o = tr.toDBObject();
		o.put("created_", new java.util.Date());
		coll.save(o, com.mongodb.WriteConcern.SAFE);
	}
	public void update_pid(iCARDEA_Patient.ID id, iCARDEA_Patient tr) {
		DBCollection coll = this.db_.getCollection(COLL_NAME);

		BasicDBObject qry = new BasicDBObject();;
		qry.append("ids", 
					new BasicDBObject("$elemMatch", id.toDBObject() ));		
		DBObject o = tr.toDBObject();
		o.put("modified_", new java.util.Date());
		o.removeField("ids");
		DBObject upd = new BasicDBObject("$set", o);
		
	System.out.println("UMONQ="+qry);
		
		coll.update(qry, upd, false, true, com.mongodb.WriteConcern.SAFE);
	}
	
	private static void addCritPattern(DBObject o, String key, String value) {
		if (value.indexOf('*') > 0) {
			value.replace("*", ".*");		
		}
		if (value.charAt(0)!='^')
			value = "^" + value;

		Pattern re = Pattern.compile(value);
		o.put(key, re);	
	}
	private static void addCritDBObj(DBObject query, PrimKV o, String prefix) {
		Map m = o.toDBObject().toMap();
		Iterator it = m.keySet().iterator();
		while(it.hasNext()) {
			Object k = it.next();
			String n = (String) k;
			String name = prefix+n;
			Object v = m.get(k);
			if (v instanceof PrimKV) {
				PrimKV pv = (PrimKV) v;
				addCritDBObj(query, pv, name+".");
			}
			// query.put(prefix+"."+n, m.get(k));
			else
				addCritPattern(query, name, (String) v);
		}			
	}
	public iCARDEA_Patient[] query(iCARDEA_Patient tr) {
		BasicDBObject query = new BasicDBObject();
		
		addCritDBObj(query, tr.name, "name.");
		addCritDBObj(query, tr.mothers_name, "mothers_name.");
		addCritDBObj(query, tr.addr, "addr.");
		
		if (tr.date_of_birth != null)
			addCritPattern(query, "date_of_birth", tr.date_of_birth);
		if (tr.sex != null)
			query.put("sex", tr.sex);
		if (tr.ssn != null) 
			addCritPattern(query, "ssn", tr.ssn);
		if (tr.tel_home != null) 
			addCritPattern(query, "tel_home", tr.tel_home);
		if (tr.tel_work != null) 
			addCritPattern(query, "tel_work", tr.tel_work);
		if (tr.drivers_lic != null) 
			addCritPattern(query, "drivers_lic", tr.drivers_lic);
		if (tr.accnum != null) 
			addCritPattern(query, "accnum", tr.accnum);
		
		ArrayList<DBObject> ids_or = new ArrayList<DBObject>();
		for (iCARDEA_Patient.ID id: tr.ids) {
			ids_or.add( id.toDBObject() );
		}
		if (ids_or.size() > 1)
			query.append("ids", 
					new BasicDBObject("$elemMatch", 
							QueryBuilder.start().or(ids_or.toArray(new DBObject[0])).get()));
		else if (ids_or.size() == 1)
			query.append("ids", 
					new BasicDBObject("$elemMatch", ids_or.get(0)));
		
System.out.println("MONQ="+query);

		DBCollection coll = this.db_.getCollection(COLL_NAME);
		final int SORT_ORDER = -1; // descending 
        DBCursor cur = coll.find(query).sort(new BasicDBObject("created_",SORT_ORDER)).limit(100);

		ArrayList<iCARDEA_Patient> patLst = new ArrayList<iCARDEA_Patient>();
        while(cur.hasNext()) {
        	iCARDEA_Patient o = iCARDEA_Patient.create_from_DBObject(cur.next());
        	patLst.add(o);
        }
        iCARDEA_Patient[] patients = new iCARDEA_Patient[0];
        return patLst.toArray(patients);
	}

	public iCARDEA_Patient retrieve(iCARDEA_Patient.ID id, String... idNS) {
		//BasicDBObject query = new BasicDBObject(iCARDEA_Patient.ID_PREFIX+id.namespace, id.id);
		BasicDBObject query = new BasicDBObject();
		query.append("ids", 
				new BasicDBObject("$elemMatch", id.toDBObject()));
		DBCollection coll = this.db_.getCollection(COLL_NAME);
		DBObject dbObj = null;
		if (idNS.length > 0) {
			DBObject fo = new BasicDBObject();
			for (String f: idNS)
				fo.put(iCARDEA_Patient.ID_PREFIX+f, 1);
			fo.put("family_name", 1);
			fo.put("given_name", 1);
			fo.put("ids", 1);
			dbObj = coll.findOne(query, fo);
			
		}
		else
			dbObj = coll.findOne(query);
		if (dbObj == null)
			return null;
		return iCARDEA_Patient.create_from_DBObject(dbObj);
	}
}

