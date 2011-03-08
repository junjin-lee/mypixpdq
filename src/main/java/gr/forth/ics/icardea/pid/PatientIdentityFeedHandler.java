package gr.forth.ics.icardea.pid;
import gr.forth.ics.icardea.mllp.HL7MLLPServer;
import gr.forth.ics.icardea.pid.iCARDEA_Patient.ID;

import java.io.IOException;

import org.apache.log4j.Logger;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.app.ApplicationException;
import ca.uhn.hl7v2.app.DefaultApplication;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.Segment;
import ca.uhn.hl7v2.model.Type;
import ca.uhn.hl7v2.model.v231.segment.MRG;
import ca.uhn.hl7v2.model.v231.segment.PID;
import ca.uhn.hl7v2.model.v231.datatype.CX;
import ca.uhn.hl7v2.model.v231.message.ACK;
import ca.uhn.hl7v2.model.v231.message.ADT_A39;
import ca.uhn.hl7v2.model.v25.message.RSP_K21;
import ca.uhn.hl7v2.util.Terser;

/**
 * Patient Identity Feed Handler (ITI TF-2a / 3.8)
 *  
 * This is for the Patient Identity Management - Admit/Register or Update
 * Patient. The following events from a Patient Identity Source Actor will
 * trigger one of the Admit/Register or Update messages:
 *  - A01 - Admission of an in-patient into a facility
 *  - A04 - Registration of an outpatient for a visit of the facility
 *  - A05 - Pre-admission of an in-patient (i.e., registration of patient
 * information ahead of actual admission).
 * 
 * Changes to patient demographics (e.g., change in patient name, patient
 * address, etc.) shall trigger the following Admit/Register or Update message:
 *  - A08 - Update Patient Information
 *  
 * The Patient Identity Feed transaction is conducted by the HL7 ADT message.
 */
final class PatientIdentityFeedHandler extends DefaultApplication {
	static Logger logger = Logger.getLogger(PatientIdentityFeedHandler.class);
	public static String[] trigEvents = new String[] {"A01", "A04", "A05", "A08", "A40"};
	public void register(HL7MLLPServer s) {
		for (String ev: trigEvents)
			s.registerApplication("ADT", ev, this);
	}

	public boolean canProcess(Message msg) {
		Terser t = new Terser(msg);
		try {
			Segment s = t.getSegment("/PID");
			if (!(s instanceof PID))
				return false;
			String trigEvent = t.get("/MSH-9-2");
			for (String ev: trigEvents)
				if (ev.equals(trigEvent))
					return true;
			return false;
		} catch (HL7Exception e) {
			return false;
		}
	}
	/**
	 * See ITI-vol2a, 3.8
	 */
	public Message processMessage(Message msg) throws ApplicationException{
		try {
			logger.debug("Received:"+msg.encode());
		} catch (HL7Exception e) {
		}
		Terser terser = new Terser(msg);
		ACK a = null;
		try {

			String trigEvent = terser.get("/MSH-9-2");
			Segment msh = (Segment) terser.getSegment("/MSH");
			a = (ACK) makeACK( msh );
			a.getMSH().getMsh9_MessageType().parse("ACK^"+trigEvent);

			HL7Utils.fillResponseHeader(msh, a.getMSH());

			if ("A40".equals(trigEvent)) {
				ADT_A39 merge = (ADT_A39) msg;

				iCARDEA_Patient.ID surv_id = null;
				PID pid = merge.getPIDPD1MRGPV1().getPID();
				for (CX cx: pid.getPid3_PatientIdentifierList()) {
					String id = cx.getCx1_ID().getValue();	
					String namespace = cx.getCx4_AssigningAuthority().getHd1_NamespaceID().getValue();
					if (namespace == null || "".equals(namespace)) {
						String uid = cx.getCx4_AssigningAuthority().getHd2_UniversalID().getValue();
						String uid_type = cx.getCx4_AssigningAuthority().getHd3_UniversalIDType().getValue();
						AssigningAuthority auth = AssigningAuthority.find_by_uid(uid, uid_type);
						if (auth == null) {
							HL7Exception ex = new HL7Exception("Unsupported authority:"+pid.getField(3, 0).encode(), HL7Exception.UNKNOWN_KEY_IDENTIFIER);
							throw ex;
						}
						namespace = auth.namespace;
					}
					surv_id = new ID(namespace, id);
					break;					
				}
				// par. 3.8.4.2
				MRG mrg = merge.getPIDPD1MRGPV1().getMRG();
				
				iCARDEA_Patient.ID old_id = null;
				for (CX cx: mrg.getMrg1_PriorPatientIdentifierList()) {
					String id = cx.getCx1_ID().getValue();	
					String namespace = cx.getCx4_AssigningAuthority().getHd1_NamespaceID().getValue();
					if (namespace == null || "".equals(namespace)) {
						String uid = cx.getCx4_AssigningAuthority().getHd2_UniversalID().getValue();
						String uid_type = cx.getCx4_AssigningAuthority().getHd3_UniversalIDType().getValue();
						AssigningAuthority auth = AssigningAuthority.find_by_uid(uid, uid_type);
						if (auth == null) {
							HL7Exception ex = new HL7Exception("Unsupported authority:"+pid.getField(3, 0).encode(), HL7Exception.UNKNOWN_KEY_IDENTIFIER);
							throw ex;
						}
						namespace = auth.namespace;
					}
					old_id = new ID(namespace, id);
					break;					
				}
				// XXX: Here we use the first (and only?) ID to find what entry to update!
				StorageManager.getInstance().merge_pid(surv_id, old_id);
			}
			else {
				Segment s = terser.getSegment("/PID");
				PID pid = (PID) s;
				Type[] tt = pid.getField(3);
				for (Type t: tt) {
					AssigningAuthority auth = null;
					String tons = Terser.getPrimitive(t, 4, 1).getValue();
					if (tons == null || "".equals(tons)) {
						String uid = Terser.getPrimitive(t, 4, 2).getValue();
						String uid_type = Terser.getPrimitive(t, 4, 3).getValue();
						auth = AssigningAuthority.find_by_uid(uid, uid_type);
					}
					else
						auth = AssigningAuthority.find(tons);
					if (auth == null) {
						HL7Exception ex = new HL7Exception("Unsupported authority:"+pid.getField(3, 0).encode(), HL7Exception.UNKNOWN_KEY_IDENTIFIER);
						throw ex;
					}
				}

				logger.debug("PID:"+pid.encode());
				iCARDEA_Patient tr = iCARDEA_Patient.create_from_PID(pid);

				if (tr.ids.size() == 0)
					throw new HL7Exception("No identifiers given", HL7Exception.DATA_TYPE_ERROR);
				if ("A08".equals(trigEvent)) {
					// XXX: Here we use the first (and only?) ID to find what entry to update!
					StorageManager.getInstance().update_pid(tr.ids.get(0), tr);
				}
				else {
					for (iCARDEA_Patient.ID id: tr.ids) {
						if (null != StorageManager.getInstance().retrieve(id))
							throw new HL7Exception("Patient exists already!", HL7Exception.DATA_TYPE_ERROR);
					}
					StorageManager.getInstance().insert_pid(tr);
				}
			}
			a.getMSA().getMsa2_MessageControlID().setValue(terser.get("/MSH-10"));
			a.getMSA().getMsa1_AcknowledgementCode().setValue("AA");

		} catch (HL7Exception e) {
			e.printStackTrace();
			try {
				a.getMSA().getMsa1_AcknowledgementCode().setValue("AE");
				// a.getMSA().getMsa3_TextMessage().setValue(e.getMessage());
				// HL7Utils.fillErrHeader(a, e);
			} catch (HL7Exception ex) {
				ex.printStackTrace();
				throw new ApplicationException(ex.getMessage(), ex);
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw new ApplicationException(e.getMessage(), e);
		}
		try {
			logger.debug("Sending:"+a.encode());
		} catch (HL7Exception e) {
		}

		return a;
	}
}

