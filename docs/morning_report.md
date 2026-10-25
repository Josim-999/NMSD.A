# Morning transport report - 2026-10-08 13:30

## Fleet
Fleet KPIs today (13:30):
  Utilisation: 84% (36 of 43 usable trucks dispatched)
  Stops completed: 56, on-time arrival: 68%
  Pallets delivered: 349
  Trucks with open exceptions: 9
  Status: AT_CUSTOMER 4, AVAILABLE 26, BREAKDOWN 1, IN_TRANSIT 6, MAINTENANCE 2, RETURNING 6

## Requires attention
9 trucks need attention at 13:30 (most urgent first):
  TRK-023 [CRITICAL] vehicle breakdown - Driver reported breakdown at 11:45: Tyre burst on Al Kharj road, waiting for road assistance
      -> Request road assistance now; if repair > 90 min dispatch the nearest same-type truck to transfer the load. [SOP-FLT-01]
  TRK-007 [HIGH] long waiting time at the customer dock - Waiting 78 min at Hospital A (planned 30 min)
      -> Call the customer's receiving contact, record reason DOCK_WAIT, escalate if waiting continues. [SOP-OPS-02]
  TRK-012 [HIGH] unplanned stop en route - Stopped 50 min away from any site (171 km from Dammam Hub)
      -> Call the driver to confirm why the truck is stopped (rest break, traffic, problem?). [SOP-OPS-02]
  TRK-024 [HIGH] accumulated waiting at earlier stops - 47 min late for Clinic D (planned 12:54, predicted 13:40)
      -> Confirm the cause with the driver and update the ETA; inform the customer if 45 min or more. [SOP-OPS-02]
  TRK-028 [HIGH] accumulated waiting at earlier stops - 93 min late for Pharmacy C (planned 12:03, predicted 13:35)
      -> Confirm the cause with the driver and update the ETA; inform the customer if 45 min or more. [SOP-OPS-02]
  TRK-031 [HIGH] no recent GPS data (cannot confirm progress) - No GPS ping for 55 min (last 12:35)
      -> Call the driver for position and raise a ticket with the FMS/GPS provider. [SOP-OPS-02]
  TRK-036 [HIGH] late departure from origin (95 min after plan) - 99 min late for Hospital N (planned 13:34, predicted 15:13)
      -> Confirm the cause with the driver and update the ETA; inform the customer if 45 min or more. [SOP-OPS-02]
  TRK-019 [MEDIUM] truck is off the planned route - 18 km off the planned corridor
      -> Confirm the route with the driver (road closure or detour?). [SOP-OPS-02]
  TRK-034 [MEDIUM] missing_info - Shipment S0340 has no manifest number
      -> Complete the manifest number in the TMS before the next gate release. [SOP-DOC-02]

## Delays
6 trucks are predicted late for their next stop:
  TRK-023 +154 min -> Hospital L (planned 12:14, ETA 14:47); cause: vehicle breakdown
  TRK-036 +99 min -> Hospital N (planned 13:34, ETA 15:13); cause: late departure from origin (95 min after plan)
  TRK-028 +93 min -> Pharmacy C (planned 12:03, ETA 13:35); cause: accumulated waiting at earlier stops
  TRK-007 +58 min -> Pharmacy C (planned 13:08, ETA 14:06); cause: long waiting time at the customer dock
  TRK-012 +50 min -> Hospital J (planned 15:19, ETA 16:09); cause: unplanned stop en route
  TRK-024 +47 min -> Clinic D (planned 12:54, ETA 13:40); cause: accumulated waiting at earlier stops

## Not yet dispatched
9 shipments are not dispatched yet:
  P01  Main Logistics Park (Riyadh) -> Hospital B  6 plt reefer  due 2026-10-08 18:00
  P04  Main Logistics Park (Riyadh) -> Pharmacy C  4 plt reefer  due 2026-10-08 18:00
  P06  Main Logistics Park (Riyadh) -> Clinic D  3 plt reefer  due 2026-10-08 18:00
  P08  Jeddah Hub -> Clinic H  5 plt reefer  due 2026-10-08 18:00
  P02  Main Logistics Park (Riyadh) -> Hospital F  16 plt reefer  due 2026-10-09 12:00
  P03  Main Logistics Park (Riyadh) -> Hospital L  20 plt dry  due 2026-10-09 12:00
  P05  Main Logistics Park (Riyadh) -> Hospital J  14 plt reefer  due 2026-10-09 12:00
  P07  Main Logistics Park (Riyadh) -> Hospital M  22 plt reefer  due 2026-10-09 12:00
  P09  Main Logistics Park (Riyadh) -> Pharmacy K  10 plt dry  due 2026-10-09 12:00

## Customers causing waiting
Customer dock performance (last 30 days), worst first:
  Hospital A   Riyadh    avg wait 36 min, waits > 30 min on 45% of 244 visits  <- add planning buffer [SOP-OPS-02]
  Hospital E   Riyadh    avg wait 34 min, waits > 30 min on 44% of 269 visits  <- add planning buffer [SOP-OPS-02]
  Hospital I   Makkah    avg wait 17 min, waits > 30 min on 15% of 88 visits
  Hospital G   Jeddah    avg wait 12 min, waits > 30 min on 8% of 89 visits
  Hospital F   Buraydah  avg wait 9 min, waits > 30 min on 3% of 149 visits
  Hospital J   Dammam    avg wait 8 min, waits > 30 min on 2% of 136 visits
  Hospital B   Riyadh    avg wait 8 min, waits > 30 min on 3% of 272 visits
  Hospital L   Al Kharj  avg wait 7 min, waits > 30 min on 3% of 134 visits
