/**
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */
package org.openmrs.module.appointment.web.controller;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Location;
import org.openmrs.api.APIException;
import org.openmrs.api.context.Context;
import org.openmrs.module.appointment.Appointment;
import org.openmrs.module.appointment.AppointmentBlock;
import org.openmrs.module.appointment.TimeSlot;
import org.openmrs.module.appointment.api.AppointmentService;
import org.openmrs.web.WebConstants;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Controller for listing appointment types.
 */
@Controller
public class AppointmentBlockListController {
	
	/** Logger for this class and subclasses */
	protected final Log log = LogFactory.getLog(getClass());
	
	@RequestMapping(value = "/module/appointment/appointmentBlockList", method = RequestMethod.GET)
	public void showForm(HttpServletRequest request, ModelMap model) throws ParseException {
		model.addAttribute("appointmentsCount", "");
		if (Context.isAuthenticated()) {
			if (request.getSession().getAttribute("chosenLocation") != null) {
				Location location = (Location) request.getSession().getAttribute("chosenLocation");
				model.addAttribute("chosenLocation", location);
			}
			String fromDate = (String) request.getSession().getAttribute("fromDate");
			String toDate = (String) request.getSession().getAttribute("toDate");
			Calendar cal = Context.getDateTimeFormat().getCalendar();
			if (fromDate == null && toDate == null) {
				cal.setTime(new Date());
				cal.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DATE), 0, 0, 0);
				fromDate = Context.getDateTimeFormat().format(cal.getTime()).toString();
				cal.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DATE), 23, 59, 59);
				cal.add(Calendar.DAY_OF_MONTH, 6);
				toDate = Context.getDateTimeFormat().format(cal.getTime()).toString();
			}
			model.addAttribute("fromDate", fromDate);
			model.addAttribute("toDate", toDate);
		}
		
	}
	
	@ModelAttribute("chosenLocation")
	public Location getLocation(@RequestParam(value = "locationId", required = false) Location location) {
		if (location != null)
			return location;
		else
			return null;
	}
	
	@RequestMapping(method = RequestMethod.POST)
	public String onSubmit(HttpServletRequest request, ModelMap model,
	        @RequestParam(value = "fromDate", required = false) Date fromDate,
	        @RequestParam(value = "toDate", required = false) Date toDate,
	        @RequestParam(value = "locationId", required = false) Location location,
	        @RequestParam(value = "appointmentBlockId", required = false) Integer appointmentBlockId,
	        @RequestParam(value = "toVoid", required = false) String toVoid) throws Exception {
		//save details from the appointment block list page using http session
		HttpSession httpSession = request.getSession();
		httpSession.setAttribute("chosenLocation", location);
		httpSession.setAttribute("fromDate", Context.getDateTimeFormat().format(fromDate).toString());
		httpSession.setAttribute("toDate", Context.getDateTimeFormat().format(toDate).toString());
		
		AppointmentBlock appointmentBlock = null;
		if (Context.isAuthenticated()) {
			AppointmentService appointmentService = Context.getService(AppointmentService.class);
			if (appointmentBlockId != null) {
				appointmentBlock = appointmentService.getAppointmentBlock(appointmentBlockId);
			}
			if (toVoid != null && toVoid.equals("true")) {
				if (appointmentBlock != null) {
					String voidReason = "Some Reason";//request.getParameter("voidReason");
					if (!(StringUtils.hasText(voidReason))) {
						httpSession.setAttribute(WebConstants.OPENMRS_ERROR_ATTR,
						    "appointment.AppointmentBlock.error.voidReasonEmpty");
						return null;
					}
					List<TimeSlot> currentTimeSlots = appointmentService.getTimeSlotsInAppointmentBlock(appointmentBlock);
					List<Appointment> appointments = new ArrayList<Appointment>();
					for (TimeSlot timeSlot : currentTimeSlots) {
						List<Appointment> appointmentsInSlot = appointmentService.getAppointmentsInTimeSlot(timeSlot);
						for (Appointment appointment : appointmentsInSlot) {
							appointments.add(appointment);
						}
					}
					//set appointments statuses to "Cancelled"
					for (Appointment appointment : appointments) {
						
						appointmentService.changeAppointmentStatus(appointment, "Cancelled");
						//TODO check with Tobin if I should delete this line
						//appointmentService.voidAppointment(appointment, voidReason);
					}
					//voiding appointment block
					appointmentService.voidAppointmentBlock(appointmentBlock, voidReason);
					//voiding time slots
					for (TimeSlot timeSlot : currentTimeSlots) {
						appointmentService.voidTimeSlot(timeSlot, voidReason);
						
					}
					httpSession.setAttribute(WebConstants.OPENMRS_MSG_ATTR,
					    "appointment.AppointmentBlock.voidedSuccessfully");
					model.addAttribute("toVoid", "false");
				}
			}
			//If the user is deleting the AppointmentBlock
			else if (request.getParameter("delete") != null) {
				if (appointmentBlock != null) {
					//appointment block can't pureged if an appointment is scheduled in it.
					List<TimeSlot> currentTimeSlots = appointmentService.getTimeSlotsInAppointmentBlock(appointmentBlock);
					List<Appointment> appointments = new ArrayList<Appointment>();
					for (TimeSlot timeSlot : currentTimeSlots) {
						List<Appointment> appointmentsInSlot = appointmentService.getAppointmentsInTimeSlot(timeSlot);
						for (Appointment appointment : appointmentsInSlot) {
							appointments.add(appointment);
						}
					}
					if (appointments.size() > 0) {
						model.addAttribute("appointmentsCount", appointments.size());
						model.addAttribute("appointmentBlockId", appointmentBlockId);
						model.addAttribute("toVoid", "true");
						return null;
					} else {
						//In case there are appointments within the appointment block we don't mind to purge it
						//purging the appointment block
						try {
							//purging the time slots
							for (TimeSlot timeSlot : currentTimeSlots) {
								appointmentService.purgeTimeSlot(timeSlot);
							}
							appointmentService.purgeAppointmentBlock(appointmentBlock);
							httpSession.setAttribute(WebConstants.OPENMRS_MSG_ATTR,
							    "appointment.AppointmentBlock.purgedSuccessfully");
						}
						catch (DataIntegrityViolationException e) {
							httpSession.setAttribute(WebConstants.OPENMRS_ERROR_ATTR, "error.object.inuse.cannot.purge");
						}
						catch (APIException e) {
							httpSession.setAttribute(WebConstants.OPENMRS_ERROR_ATTR, "error.general: "
							        + e.getLocalizedMessage());
						}
					}
					return null;
				} else
					httpSession.setAttribute(WebConstants.OPENMRS_MSG_ATTR,
					    "appointment.AppointmentBlock.error.selectAppointmentBlock");
			}

			// if the user is unvoiding the AppointmentBlock
			else if (request.getParameter("unvoid") != null) {
				List<TimeSlot> currentTimeSlots = appointmentService.getTimeSlotsInAppointmentBlock(appointmentBlock);
				List<Appointment> appointmentsThatShouldBeUnvoided = new ArrayList<Appointment>();
				for (TimeSlot timeSlot : currentTimeSlots) {
					List<Appointment> currentAppointments = appointmentService.getAppointmentsInTimeSlot(timeSlot);
					for (Appointment appointment : currentAppointments) {
						if (!appointmentsThatShouldBeUnvoided.contains(appointment))
							appointmentsThatShouldBeUnvoided.add(appointment);
					}
				}
				//unvoiding the appointment block
				appointmentService.unvoidAppointmentBlock(appointmentBlock);
				//unvoiding the appointments
				for (Appointment appointment : appointmentsThatShouldBeUnvoided) {
					appointmentService.unvoidAppointment(appointment);
				}
				//unvoiding the time slots
				for (TimeSlot timeSlot : currentTimeSlots) {
					appointmentService.unvoidTimeSlot(timeSlot);
				}
				
				httpSession.setAttribute(WebConstants.OPENMRS_MSG_ATTR, "appointment.AppointmentBlock.unvoidedSuccessfully");
			}

			// if the user is adding a new AppointmentBlock
			else if (request.getParameter("add") != null) {
				return "redirect:appointmentBlockForm.form";
			}

			// if the user is editing an existing AppointmentBlock
			else if (request.getParameter("edit") != null) {
				if (appointmentBlockId != null) {
					return "redirect:appointmentBlockForm.form?appointmentBlockId=" + appointmentBlockId;
				} else {
					httpSession.setAttribute(WebConstants.OPENMRS_MSG_ATTR,
					    "appointment.AppointmentBlock.error.selectAppointmentBlock");
				}
			}
			
		}
		return null;
	}
}
