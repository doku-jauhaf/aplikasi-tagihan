package id.ac.tazkia.payment.virtualaccount.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.ac.tazkia.payment.virtualaccount.dao.*;
import id.ac.tazkia.payment.virtualaccount.dto.*;
import id.ac.tazkia.payment.virtualaccount.entity.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Validator;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional
public class KafkaListenerService {
    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaListenerService.class);

    @Value("${kode.biaya.default}")
    private String idKodeBiayaDefault;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private Validator validator;

    @Autowired
    private VirtualAccountDao virtualAccountDao;
    @Autowired
    private BankDao bankDao;
    @Autowired
    private KodeBiayaDao kodeBiayaDao;
    @Autowired
    private TagihanDao tagihanDao;
    @Autowired
    private PembayaranDao pembayaranDao;
    @Autowired
    private DebiturDao debiturDao;
    @Autowired
    private JenisTagihanDao jenisTagihanDao;
    @Autowired
    private PeriksaStatusTagihanDao periksaStatusTagihanDao;
    @Autowired
    private TagihanService tagihanService;
    @Autowired
    private KafkaSenderService kafkaSenderService;

    private KodeBiaya kodeBiayaDefault;

    @PostConstruct
    public void inisialisasiKodeBiaya() {
        LOGGER.debug("ID kode biaya default : {}", idKodeBiayaDefault);
        kodeBiayaDefault = kodeBiayaDao.findById(idKodeBiayaDefault).get();
        LOGGER.debug("Kode biaya default : {}", kodeBiayaDefault);
    }

    @KafkaListener(topics = "${kafka.topic.debitur.request}", groupId = "${spring.kafka.consumer.group-id}")

    public void handleDebiturRequest(String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            LOGGER.debug("Terima message : {}", message);
            Debitur d = objectMapper.readValue(message, Debitur.class);
            BeanPropertyBindingResult binder = new BeanPropertyBindingResult(d, "debitur");
            validator.validate(d, binder);

            if (binder.hasErrors()) {
                LOGGER.warn("Gagal mendaftarkan debitur {}", binder.getAllErrors());
                response.put("sukses", false);
                response.put("data", binder.getAllErrors());
                kafkaSenderService.sendDebiturResponse(response);
                return;
            }

            if (debiturDao.findByNomorDebitur(d.getNomorDebitur()) != null) {
                response.put("sukses", true);
                response.put("data", "Nomor debitur " + d.getNomorDebitur() + " sudah ada");
                response.put("nomorDebitur", d.getNomorDebitur());
                kafkaSenderService.sendDebiturResponse(response);
                return;
            }

            debiturDao.save(d);
            response.put("sukses", true);
            response.put("nomorDebitur", d.getNomorDebitur());
            kafkaSenderService.sendDebiturResponse(response);
        } catch (Exception err) {
            LOGGER.warn(err.getMessage(), err);
            response.put("sukses", false);
            response.put("data", err.getMessage());
            kafkaSenderService.sendDebiturResponse(response);
        }
    }

    @KafkaListener(topics = "${kafka.topic.tagihan.request}", groupId = "${spring.kafka.consumer.group-id}")
    public void handleTagihanRequest(String message) {
        TagihanResponse response = new TagihanResponse();
        try {
            LOGGER.debug("Terima message : {}", message);
            TagihanRequest request = objectMapper.readValue(message, TagihanRequest.class);

            response.setSukses(true);
            BeanUtils.copyProperties(request, response);

            Tagihan t = new Tagihan();

            Debitur d = debiturDao.findByNomorDebitur(request.getDebitur());
            if (d == null) {
                LOGGER.warn("Debitur dengan nomor {} tidak terdaftar", request.getDebitur());
                response.setSukses(false);
                response.setError("Debitur dengan nomor " + request.getDebitur() + " tidak terdaftar");
                kafkaSenderService.sendTagihanResponse(response);
                return;
            }
            t.setDebitur(d);

            Optional<JenisTagihan> jt = jenisTagihanDao.findById(request.getJenisTagihan());
            if (!jt.isPresent()) {
                LOGGER.warn("Jenis Tagihan dengan id {} tidak terdaftar", request.getJenisTagihan());
                response.setSukses(false);
                response.setError("Jenis Tagihan dengan id " + request.getJenisTagihan() + " tidak terdaftar");
                kafkaSenderService.sendTagihanResponse(response);
                return;
            }
            t.setJenisTagihan(jt.get());

            LOGGER.debug("Kode biaya request : {}", request.getKodeBiaya());
            if (!StringUtils.hasText(request.getKodeBiaya())) {
                t.setKodeBiaya(kodeBiayaDefault);
            } else {
                Optional<KodeBiaya> kodeBiaya = kodeBiayaDao.findById(request.getKodeBiaya());
                if (!kodeBiaya.isPresent()) {
                    LOGGER.warn("Kode biaya dengan id {} tidak terdaftar", request.getKodeBiaya());
                    kodeBiaya = Optional.of(kodeBiayaDefault);
                }
                t.setKodeBiaya(kodeBiaya.get());
            }
            LOGGER.debug("Kode Biaya Tagihan: {}", t.getKodeBiaya());

            t.setNilaiTagihan(request.getNilaiTagihan());
            t.setKeterangan(request.getKeterangan());
            t.setTanggalJatuhTempo(request.getTanggalJatuhTempo());

            tagihanService.saveTagihan(t);
        } catch (Exception err) {
            LOGGER.warn(err.getMessage(), err);
            response.setSukses(false);
            response.setError(err.getMessage());
            kafkaSenderService.sendTagihanResponse(response);
        }
    }

    @KafkaListener(topics = "${kafka.topic.va.response}", groupId = "${spring.kafka.consumer.group-id}")
    public void handleVaResponse(String message) {
        try {
            LOGGER.debug("Terima message : {}", message);
            VaResponse vaResponse = objectMapper.readValue(message, VaResponse.class);

            List<VirtualAccount> daftarVa = virtualAccountDao.findByVaStatusAndTagihanNomor(VaStatus.SEDANG_PROSES, vaResponse.getInvoiceNumber());
            if (daftarVa == null || daftarVa.isEmpty()) {
                LOGGER.warn("VA untuk tagihan dengan nomor {} tidak ditemukan", vaResponse.getInvoiceNumber());
                return;
            }

            VirtualAccount va = null;
            for (VirtualAccount v : daftarVa) {
                if (vaResponse.getBankId().equals(v.getBank().getId())) {
                    va = v;
                    break;
                }
            }

            if (va == null) {
                LOGGER.warn("VA untuk tagihan dengan nomor {} dan bank {} tidak ditemukan",
                        vaResponse.getInvoiceNumber(),
                        vaResponse.getBankId()
                );
                return;
            }

            if (VaStatus.INQUIRY.equals(vaResponse.getRequestType())) {
                List<PeriksaStatusTagihan> daftarPeriksaStatus = periksaStatusTagihanDao.findByVirtualAccountAndStatusPemeriksaanTagihan(va, StatusPemeriksaanTagihan.BARU);
                if (daftarPeriksaStatus == null || daftarPeriksaStatus.isEmpty()) {
                    LOGGER.warn("Pemeriksaan status untuk VA {} di bank {} tidak ada", va.getNomor(), va.getBank().getNama());
                    return;
                }

                for (PeriksaStatusTagihan p : daftarPeriksaStatus) {
                    p.setStatusPemeriksaanTagihan(
                            VaRequestStatus.SUCCESS.equals(vaResponse.getRequestStatus()) ?
                                    StatusPemeriksaanTagihan.SUKSES : StatusPemeriksaanTagihan.ERROR);
                }
            }

            if (VaRequestStatus.ERROR.equals(vaResponse.getRequestStatus())) {
                va.setVaStatus(VaStatus.ERROR);
                virtualAccountDao.save(va);
                return;
            }

            if (VaStatus.DELETE.equals(vaResponse.getRequestType())) {
                va.setVaStatus(VaStatus.NONAKTIF);
                virtualAccountDao.save(va);
                return;
            }

            va.setNomor(vaResponse.getAccountNumber());
            va.setVaStatus(VaStatus.AKTIF);
            virtualAccountDao.save(va);

        } catch (Exception err) {
            LOGGER.warn(err.getMessage(), err);
        }
    }

    @KafkaListener(topics = "${kafka.topic.va.payment}", groupId = "${spring.kafka.consumer.group-id}")
    public void handleVaPayment(String message) {
        try {
            LOGGER.debug("Terima message : {}", message);
            VaPayment payment = objectMapper.readValue(message, VaPayment.class);
            Optional<Bank> bank = bankDao.findById(payment.getBankId());
            if (!bank.isPresent()) {
                LOGGER.warn("Bank dengan ID {} tidak terdaftar", payment.getBankId());
                return;
            }

            Tagihan tagihan = tagihanDao.findByNomor(payment.getInvoiceNumber());
            if (tagihan == null) {
                LOGGER.warn("Tagihan dengan nomor {} tidak terdaftar", payment.getInvoiceNumber());
                return;
            }
            if (StatusPembayaran.LUNAS.equals(tagihan.getStatusPembayaran())) {
                LOGGER.warn("Tagihan dengan nomor {} sudah lunas", tagihan.getNomor());
                return;
            }

            List<VirtualAccount> daftarVa = virtualAccountDao.findByVaStatusAndTagihanNomor(VaStatus.AKTIF, tagihan.getNomor());
            if (daftarVa == null || daftarVa.isEmpty()) {
                LOGGER.warn("Nomor tagihan {} tidak memiliki VA", tagihan.getNomor());
                return;
            }

            BigDecimal akumulasiPembayaran = tagihan.getJumlahPembayaran().add(payment.getAmount());
            if (akumulasiPembayaran.compareTo(tagihan.getNilaiTagihan()) > 0) {
                LOGGER.warn("Nilai pembayaran [{}] lebih besar daripada nilai tagihan [{}] nomor [{}]",
                        akumulasiPembayaran, tagihan.getNilaiTagihan(), tagihan.getNomor());
                return;
            }
            if (akumulasiPembayaran.compareTo(tagihan.getNilaiTagihan()) < 0) {
                tagihan.setStatusPembayaran(StatusPembayaran.DIBAYAR_SEBAGIAN);
            } else {
                tagihan.setStatusPembayaran(StatusPembayaran.LUNAS);
                tagihan.setStatusTagihan(StatusTagihan.NONAKTIF);
            }
            tagihan.setJumlahPembayaran(akumulasiPembayaran);

            // update VA
            VirtualAccount vaPembayaran = null;
            for (VirtualAccount va : daftarVa) {
                if (bank.get().getId().equalsIgnoreCase(va.getBank().getId())) {
                    vaPembayaran = va;
                    va.setVaStatus(StatusPembayaran.LUNAS.equals(tagihan.getStatusPembayaran())
                            ? VaStatus.NONAKTIF : VaStatus.UPDATE);
                } else {
                    va.setVaStatus(StatusPembayaran.LUNAS.equals(tagihan.getStatusPembayaran())
                            ? VaStatus.DELETE : VaStatus.UPDATE);
                }
                virtualAccountDao.save(va);
            }

            if (vaPembayaran == null) {
                LOGGER.warn("Virtual account untuk nomor tagihan {} dan bank {} tidak terdaftar",
                        tagihan.getNomor(), bank.get().getNama());
                return;
            }

            Pembayaran p = new Pembayaran();
            p.setBank(bank.get());
            p.setTagihan(tagihan);
            p.setJenisPembayaran(JenisPembayaran.VIRTUAL_ACCOUNT);
            p.setVirtualAccount(vaPembayaran);
            p.setJumlah(payment.getAmount());
            p.setReferensi(payment.getReference());
            p.setKeterangan("Pembayaran melalui VA Bank " + bank.get().getNama() + " Nomor " + payment.getAccountNumber());
            p.setWaktuTransaksi(payment.getPaymentTime());
            pembayaranDao.save(p);

            tagihanDao.save(tagihan);

            LOGGER.info("Pembayaran melalui VA Bank {} Nomor {} telah diterima", bank.get().getNama(), payment.getAccountNumber());

            kafkaSenderService.sendNotifikasiPembayaran(p);
        } catch (Exception err) {
            LOGGER.warn(err.getMessage(), err);
        }
    }
}
