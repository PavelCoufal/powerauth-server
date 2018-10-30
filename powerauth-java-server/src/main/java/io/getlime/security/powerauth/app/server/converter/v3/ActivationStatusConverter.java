/*
 * PowerAuth Server and related software components
 * Copyright (C) 2018 Wultra s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.getlime.security.powerauth.app.server.converter.v3;

/**
 * Converter class between {@link io.getlime.security.powerauth.v3.ActivationStatus} and
 * {@link io.getlime.security.powerauth.app.server.database.model.ActivationStatus}.
 *
 * @author Petr Dvorak, petr@wultra.com
 */
public class ActivationStatusConverter {

    public io.getlime.security.powerauth.v3.ActivationStatus convert(io.getlime.security.powerauth.app.server.database.model.ActivationStatus activationStatus) {
        switch (activationStatus) {
            case CREATED:
                return io.getlime.security.powerauth.v3.ActivationStatus.CREATED;
            case OTP_USED:
                return io.getlime.security.powerauth.v3.ActivationStatus.OTP_USED;
            case ACTIVE:
                return io.getlime.security.powerauth.v3.ActivationStatus.ACTIVE;
            case BLOCKED:
                return io.getlime.security.powerauth.v3.ActivationStatus.BLOCKED;
            case REMOVED:
                return io.getlime.security.powerauth.v3.ActivationStatus.REMOVED;
        }
        return io.getlime.security.powerauth.v3.ActivationStatus.REMOVED;
    }

}
